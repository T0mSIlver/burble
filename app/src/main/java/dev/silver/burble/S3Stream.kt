package dev.silver.burble

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

/** What a request says about the dictation, beyond its audio. */
data class S3Request(
    /** BCP-47 tag of the main spoken language. */
    val language: String = "en-US",
    /** Other languages the speaker mixes in, sent as Gboard sends its other keyboard languages. */
    val extraLanguages: List<String> = emptyList(),
    /** Package of the app being typed into; Gboard omits the field when it has none. */
    val triggerApp: String? = null,
    val maskOffensiveWords: Boolean = false,
)

/** One server session's audio sink, as [DictationSession] drives it. */
interface Recognizer {
    fun start()
    /** Queues Ogg bytes of any length. */
    fun feed(chunk: ByteArray)
    /** Ends the audio; the result follows. */
    fun finish()
    /** Drops the session; no result follows. */
    fun cancel()
}

/**
 * Client for Gboard's Rambler dictation backend: gRPC bidi streaming to
 * speech.s3.S3StubbyClientConnectorService/S3Bidi with hand-encoded protobuf.
 * Field numbers reverse-engineered from Gboard 18.5.3 (aatd/aasw/aasv/aatf/aatb/aawg/aatk).
 */
class S3Stream(
    private val request: S3Request,
    private val onDone: (Result<String>) -> Unit,
    client: OkHttpClient = S3.client(),
) : Recognizer {
    private val http = client
    /** Encoded Ogg Opus bytes; the empty array marks the end of audio. */
    private val queue = LinkedBlockingQueue<ByteArray>()
    private var call: Call? = null
    @Volatile private var finished = false
    private var finalText: String? = null

    override fun feed(chunk: ByteArray) {
        if (chunk.isNotEmpty()) queue.put(chunk)
    }

    override fun finish() {
        queue.put(END)
    }

    override fun cancel() {
        finished = true
        queue.clear()
        queue.put(END)
        call?.cancel()
    }

    // ---- streaming ----

    override fun start() {
        val t0 = System.nanoTime()
        fun elapsedMs() = (System.nanoTime() - t0) / 1_000_000
        val body = object : RequestBody() {
            override fun contentType() = "application/grpc".toMediaType()
            override fun isOneShot() = true

            override fun writeTo(sink: BufferedSink) {
                sink.write(grpcFrame(s3Header(request, elapsedMs())))
                val chunks = Chunker(Identity.CHUNK)
                while (true) {
                    val bytes = queue.poll(120, TimeUnit.SECONDS)
                        ?: throw IOException("no audio within 120 s")
                    val end = bytes === END
                    for (c in if (end) chunks.drain() else chunks.add(bytes)) {
                        sink.write(grpcFrame(s3Audio(c, elapsedMs())))
                    }
                    if (end) break
                }
                sink.write(grpcFrame(S3_END))
            }
        }
        val req = Request.Builder()
            .url(Identity.URL)
            .header("te", "trailers")
            .header("x-goog-api-key", Identity.API_KEY)
            .header("x-android-package", Identity.PACKAGE)
            .header("x-android-cert", Identity.CERT)
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
    }
}

// ---- request encoding ----
// Protobuf writes fields in number order, extensions among them, so these do too: the bytes match
// what Gboard 18.5.3's S3HeaderProducer and S3RequestMutator produce for a voice-typing session.

/**
 * aatd header: recognizer name, then extensions aatf (locales), aasw (audio), aatb (client),
 * aawg (recognizer options) and aatk. [elapsedMs] runs from the session start, like Gboard's
 * stopwatch.
 */
internal fun s3Header(r: S3Request, elapsedMs: Long): ByteArray {
    // Gboard renames these primary locales for the server; secondary ones go as they are.
    val primary = when (r.language) {
        "zh-CN" -> "zh-Hans"
        "zh-TW" -> "zh-Hant"
        "zh-HK" -> "yue"
        else -> r.language
    }
    fun locale(tag: String) = Pb.str(1, tag) + Pb.int(2, 1)
    val aatf = r.extraLanguages.filter { it != r.language }.distinct()
        .fold(Pb.msg(2, locale(primary))) { acc, tag -> acc + Pb.msg(4, locale(tag)) } +
        Pb.bool(20, true)
    // 16 kHz, OGG_OPUS (10), one channel.
    val aasw = Pb.f32(2, 16000.0f) + Pb.int(3, 10) + Pb.int(4, 1) + Pb.int(9, elapsedMs)
    val aatb = Pb.str(2, Identity.CLIENT) + Pb.str(8, "Android") +
        Pb.str(9, android.os.Build.DISPLAY ?: "") + Pb.str(10, Identity.VERSION) +
        Pb.str(11, android.os.Build.MODEL ?: "") +
        (r.triggerApp?.takeIf { it.isNotEmpty() }?.let { Pb.str(16, it) } ?: ByteArray(0)) +
        Pb.msg(22, Pb.bool(1, false)) + Pb.int(26, 1)
    // f7 is 2 when Gboard's "Block offensive words" is on. f40, f91.2 and aatf f20 follow
    // Gboard's server-tuned flags, whose live values aren't known; these are their defaults.
    val aawg = Pb.msg(1, Pb.bool(4, false)) + Pb.bool(5, true) +
        Pb.int(7, if (r.maskOffensiveWords) 2 else 0) + Pb.bool(13, true) + Pb.bool(20, true) +
        Pb.bool(40, false) + Pb.bool(41, true) + Pb.str(50, Identity.ROUTING_KEY) +
        Pb.msg(91, Pb.bool(2, true))
    return Pb.str(1, Identity.RECOGNIZER) + Pb.bool(2, true) +
        Pb.msg(293000, aatf) + Pb.msg(293100, aasw) + Pb.msg(294000, aatb) +
        Pb.msg(294500, aawg) + Pb.msg(29735924, Pb.int(1, 500)) +
        // aatr f5: the custom instruction Gboard sends from its Rambler settings. Without it the
        // server treats instruction-like sentences ("keep it minimal") as edits and drops them.
        Pb.msg(1203, Pb.str(5, Identity.CUSTOM_INSTRUCTION))
}

/** aatd audio: aasw with the send time, then aasv with the Ogg bytes. */
internal fun s3Audio(chunk: ByteArray, elapsedMs: Long): ByteArray =
    Pb.msg(293100, Pb.int(9, elapsedMs)) + Pb.msg(293101, Pb.bytes(1, chunk))

/** aatd end of data. */
internal val S3_END: ByteArray = Pb.bool(2, true) + Pb.bool(3, true)

/** gRPC frame: 1-byte compressed flag + 4-byte big-endian length + payload. */
internal fun grpcFrame(payload: ByteArray): ByteArray =
    byteArrayOf(0, (payload.size shr 24).toByte(), (payload.size shr 16).toByte(),
        (payload.size shr 8).toByte(), payload.size.toByte()) + payload

/** Cuts bytes arriving in any lengths into [size]-byte pieces; [drain] returns what is left. */
internal class Chunker(private val size: Int) {
    private val buf = java.io.ByteArrayOutputStream()

    fun add(bytes: ByteArray): List<ByteArray> {
        buf.write(bytes)
        if (buf.size() < size) return emptyList()
        val all = buf.toByteArray()
        val whole = all.size / size * size
        buf.reset()
        buf.write(all, whole, all.size - whole)
        return (0 until whole step size).map { all.copyOfRange(it, it + size) }
    }

    fun drain(): List<ByteArray> =
        buf.toByteArray().also { buf.reset() }.let { if (it.isEmpty()) emptyList() else listOf(it) }
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
