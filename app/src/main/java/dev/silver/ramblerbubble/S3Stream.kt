package dev.silver.ramblerbubble

import okhttp3.Call
import okhttp3.Callback
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.Response
import okio.BufferedSink
import java.io.IOException
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

/**
 * Client for Gboard's Rambler dictation backend: gRPC bidi streaming to
 * speech.s3.S3StubbyClientConnectorService/S3Bidi with hand-encoded protobuf.
 * Field numbers reverse-engineered from Gboard 18.5.3 (aatd/aasw/aasv/aatf/aatb/aawg/aatk).
 */
class S3Stream(
    private val onPartial: (String) -> Unit,
    private val onDone: (Result<String>) -> Unit,
    /** BCP-47 tag of the spoken language. */
    private val language: String = "en-US",
    client: OkHttpClient = S3.client(),
) {
    private val http = client
    /** Encoded Ogg Opus bytes; the empty array marks the end of audio. */
    private val queue = LinkedBlockingQueue<ByteArray>()
    private var call: Call? = null
    @Volatile private var finished = false
    private var finalText: String? = null

    fun feed(chunk: ByteArray) {
        if (chunk.isNotEmpty()) queue.put(chunk)
    }

    fun finish() {
        queue.put(END)
    }

    fun cancel() {
        finished = true
        queue.clear()
        queue.put(END)
        call?.cancel()
    }

    // ---- request encoding ----

    private fun headerMessage(): ByteArray {
        val aasw = Pb.int(3, 10) + Pb.f32(2, 16000.0f) + Pb.int(4, 1)
        val aatf = Pb.msg(2, Pb.str(1, language)) + Pb.bool(20, true)
        val aasz = Pb.bool(1, false)
        val aatb = Pb.str(8, "Android") + Pb.str(9, android.os.Build.DISPLAY) +
            Pb.str(11, android.os.Build.MODEL) + Pb.msg(22, aasz) +
            Pb.str(2, "gboard") + Pb.str(16, "com.google.android.googlequicksearchbox") +
            Pb.str(10, "-1") + Pb.int(26, 1)
        val aawg = Pb.bool(5, true) + Pb.bool(13, true) + Pb.bool(20, true) +
            Pb.bool(40, false) + Pb.int(7, 0) + Pb.bool(41, true) +
            Pb.msg(1, Pb.bool(4, false)) + Pb.msg(91, Pb.bool(2, true)) +
            Pb.str(50, "gboard_gemini_v3_streaming_voice_edit_mul")
        return Pb.str(1, "beyond-a2a-recognizer") + Pb.bool(2, true) +
            Pb.msg(293100, aasw) + Pb.msg(293000, aatf) + Pb.msg(294000, aatb) +
            Pb.msg(294500, aawg) + Pb.msg(29735924, Pb.int(1, 500))
    }

    private fun audioMessage(chunk: ByteArray, elapsedMs: Long) =
        Pb.msg(293101, Pb.bytes(1, chunk)) + Pb.msg(293100, Pb.int(9, elapsedMs))

    private fun endMessage() = Pb.bool(2, true) + Pb.bool(3, true)

    /** gRPC frame: 1-byte compressed flag + 4-byte big-endian length + payload. */
    private fun frame(payload: ByteArray): ByteArray =
        byteArrayOf(0, (payload.size shr 24).toByte(), (payload.size shr 16).toByte(),
            (payload.size shr 8).toByte(), payload.size.toByte()) + payload

    // ---- streaming ----

    fun start() {
        val body = object : RequestBody() {
            override fun contentType() = "application/grpc".toMediaType()
            override fun isOneShot() = true

            override fun writeTo(sink: BufferedSink) {
                sink.write(frame(headerMessage()))
                var sent = 0L
                while (true) {
                    val chunk = queue.poll(120, TimeUnit.SECONDS)
                        ?: throw IOException("no audio within 120 s")
                    if (chunk === END || chunk.isEmpty()) break
                    sink.write(frame(audioMessage(chunk, sent * 8 / 24)))
                    sent += chunk.size
                }
                sink.write(frame(endMessage()))
            }
        }
        val req = Request.Builder()
            .url("https://speechs3proto2-pa.googleapis.com/speech.s3.S3StubbyClientConnectorService/S3Bidi")
            .header("te", "trailers")
            .header("x-goog-api-key", API_KEY)
            .header("x-android-package", PACKAGE)
            .header("x-android-cert", CERT)
            .post(body)
            .build()
        call = http.newCall(req)
        call?.enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                complete(Result.failure(e))
            }

            override fun onResponse(call: Call, response: Response) {
                response.use { resp ->
                    val earlyStatus = resp.header("grpc-status")
                    if (!resp.isSuccessful || (earlyStatus != null && earlyStatus != "0")) {
                        // A trailers-only error carries its gRPC status in the headers; a proxy
                        // error has only the HTTP one.
                        val code = earlyStatus?.toIntOrNull() ?: grpcStatusOf(resp.code)
                        complete(Result.failure(
                            S3Exception(code, resp.header("grpc-message") ?: resp.message)))
                        return
                    }
                    readResponses(resp)
                }
            }
        })
    }

    private fun readResponses(resp: Response) {
        val src = resp.body?.byteStream()
            ?: run { complete(Result.failure(S3Exception(-1, "empty body"))); return }
        try {
            while (true) {
                val lenBuf = ByteArray(5)
                if (readFully(src, lenBuf) < 5) break
                if (lenBuf[0].toInt() != 0) throw S3Exception(-1, "compressed frame")
                val len = (lenBuf[1].toInt() and 0xFF shl 24) or
                    (lenBuf[2].toInt() and 0xFF shl 16) or
                    (lenBuf[3].toInt() and 0xFF shl 8) or
                    (lenBuf[4].toInt() and 0xFF)
                if (len > 4_000_000) throw S3Exception(-1, "oversized frame $len")
                val payload = ByteArray(len)
                if (readFully(src, payload) < len) break
                handleMessage(payload)
            }
            val trailers = resp.trailers()
            val status = trailers["grpc-status"] ?: "0"
            if (status != "0") {
                complete(Result.failure(S3Exception(status.toInt(),
                    trailers["grpc-message"] ?: "trailer failure")))
                return
            }
            val text = finalText
            // A clean end with no transcript (silence) is an empty result, not a failure.
            complete(Result.success(text ?: ""))
        } catch (e: Exception) {
            complete(Result.failure(e))
        }
    }

    // ---- response decoding ----

    /**
     * aate: f1 status enum, f2 gRPC code, f3 message; extension 1253625 (aawe) carries results.
     * aawe f1 = end-of-utterance event; its f3 = span holder {f1: 0, f2: end-us, f3: span};
     * span f1 = the text.
     */
    private fun handleMessage(payload: ByteArray) {
        val status = Pb.firstVarint(payload, 1)
        if (status != null && status != 0L && status != 1L) {
            val code = (Pb.firstVarint(payload, 2) ?: -1).toInt()
            val msg = Pb.first(payload, 3)?.toString(Charsets.UTF_8) ?: ""
            complete(Result.failure(S3Exception(code, msg)))
            return
        }
        val aawe = Pb.first(payload, 1253625) ?: return
        val endOfUtterance = Pb.first(aawe, 1) ?: return
        val spanHolder = Pb.first(endOfUtterance, 3) ?: return
        val span = Pb.first(spanHolder, 3) ?: return
        val text = Pb.first(span, 1)?.toString(Charsets.UTF_8) ?: return
        finalText = text
        onPartial(text)
    }

    private fun readFully(src: java.io.InputStream, buf: ByteArray): Int {
        var off = 0
        while (off < buf.size) {
            val n = src.read(buf, off, buf.size - off)
            if (n == -1) break
            off += n
        }
        return off
    }

    private fun complete(r: Result<String>) {
        if (finished) return
        finished = true
        onDone(r)
    }

    companion object {
        private val END = ByteArray(0)
        const val API_KEY = "AIzaSyDbHU30I-v5OpOJm1-uff09-NJbd6I8InU"
        const val PACKAGE = "com.google.android.googlequicksearchbox"
        const val CERT = "58E1C4133F7441EC3D2C270270A14802DA47BA0E"
    }
}

/** [grpcStatus] is the gRPC status code, -1 when the response broke the protocol. */
class S3Exception(val grpcStatus: Int, message: String) : Exception("gRPC $grpcStatus: $message")

/** The gRPC status an HTTP error stands for, per the gRPC HTTP/2 spec; 429 is quota here. */
internal fun grpcStatusOf(http: Int): Int = when (http) {
    400 -> 13 // INTERNAL
    401 -> 16 // UNAUTHENTICATED
    403 -> 7 // PERMISSION_DENIED
    404 -> 12 // UNIMPLEMENTED
    429 -> 8 // RESOURCE_EXHAUSTED
    502, 503, 504 -> 14 // UNAVAILABLE
    else -> 2 // UNKNOWN
}

object S3 {
    fun client(): OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(120, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .retryOnConnectionFailure(false)
        .build()
}
