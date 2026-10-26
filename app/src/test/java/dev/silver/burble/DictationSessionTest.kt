package dev.silver.burble

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.ByteArrayOutputStream

@RunWith(RobolectricTestRunner::class)
class DictationSessionTest {
    @get:Rule val tmp = TemporaryFolder()

    /** Passes PCM through as its "Ogg" and ends each stream with a marker byte. */
    private class PassThrough : AudioEncoder {
        override fun encode(pcm: ByteArray) = pcm
        override fun flush() = byteArrayOf(-1)
    }

    /** One server session; [answer] decides its result when the audio ends. */
    private inner class Fake(val onDone: (Result<Heard>) -> Unit) : Recognizer {
        val bytes = ByteArrayOutputStream()
        override fun start() {}
        override fun feed(chunk: ByteArray) = bytes.write(chunk)
        override fun finish() = onDone(answer(sessions.indexOf(this), bytes.toByteArray()))
        override fun cancel() {}
    }

    private val sessions = ArrayList<Fake>()
    private var answer: (Int, ByteArray) -> Result<Heard> = { i, _ -> Result.success(Heard("part$i")) }
    private val results = ArrayList<Result<Heard>>()

    private fun session(limits: DictationSession.Limits = DictationSession.Limits(), silenceMs: Long = 0) =
        DictationSession(
            dir = tmp.newFolder(),
            newRecognizer = { done -> Fake(done).also { sessions.add(it) } },
            onResult = { results.add(it) },
            silenceMs = silenceMs,
            newEncoder = ::PassThrough,
            limits = limits,
        )

    private var counter = 0

    /** 100 ms of PCM, near -6 dBFS or under -60; loud buffers differ from one another. */
    private fun pcm(loud: Boolean = true): ByteArray {
        counter++
        return ByteArray(3200) { i ->
            when {
                i % 2 == 1 -> if (loud) 0x40 else 0
                loud -> (counter + i / 2).toByte()
                else -> (i / 2 % 8).toByte()
            }
        }
    }

    @Test fun longDictationChainsWithoutLosingAudio() {
        val s = session(DictationSession.Limits(switchFromMs = 1000, switchByMs = 2000))
        val fed = ByteArrayOutputStream()
        s.begin(0)
        for (t in 0 until 70) pcm().also { fed.write(it); s.audio(it, t * 100L) }
        s.endAudio()

        assertEquals(4, sessions.size) // 0–2 s, 2–4 s, 4–6 s, 6–7 s
        assertEquals(listOf(Result.success(Heard("part0 part1 part2 part3"))), results)
        val sent = sessions.map { it.bytes.toByteArray() }
        sent.forEach { assertEquals(-1, it.last().toInt()) }
        assertArrayEquals(fed.toByteArray(), sent.fold(ByteArray(0)) { a, b -> a + b.copyOf(b.size - 1) })
    }

    @Test fun switchesAtTheFirstQuietBufferInTheWindow() {
        val s = session(DictationSession.Limits(switchFromMs = 1000, switchByMs = 2000))
        s.begin(0)
        for (t in 0 until 30) s.audio(pcm(loud = t != 13), t * 100L)
        s.endAudio()
        // 13 * 3200 bytes of audio, plus the end marker.
        assertEquals(13 * 3200 + 1, sessions[0].bytes.size())
    }

    @Test fun retrySendsTheSameBytes() {
        val s = session()
        // The first session drops while recording; it and the automatic resend fail.
        answer = { i, _ -> if (i < 2) Result.failure(java.io.IOException("offline")) else Result.success(Heard("hello")) }
        s.begin(0)
        for (t in 0 until 10) {
            s.audio(pcm(), t * 100L)
            if (t == 3) sessions[0].onDone(Result.failure(java.io.IOException("offline")))
        }
        s.endAudio()

        assertTrue(results.single().isFailure)
        assertTrue(s.retry())
        assertEquals(Result.success(Heard("hello")), results.last())
        assertEquals(3, sessions.size)
        assertArrayEquals(sessions[1].bytes.toByteArray(), sessions[2].bytes.toByteArray())
        assertEquals(10 * 3200 + 1, sessions[2].bytes.size())
        assertFalse(s.retry())
    }

    @Test fun retryResendsOnlyTheFailedSegment() {
        val s = session(DictationSession.Limits(switchFromMs = 1000, switchByMs = 1000))
        var failSecond = true
        answer = { i, _ -> if (i == 1 && failSecond) Result.failure(S3Exception(14, "unavailable")) else Result.success(Heard("part$i")) }
        s.begin(0)
        for (t in 0 until 15) s.audio(pcm(), t * 100L)
        s.endAudio()
        assertTrue(results.single().isFailure)

        failSecond = false
        assertTrue(s.retry())
        assertEquals(3, sessions.size)
        assertArrayEquals(sessions[1].bytes.toByteArray(), sessions[2].bytes.toByteArray())
        assertEquals(Result.success(Heard("part0 part2")), results.last())
    }

    @Test fun aStoppedProcessLeavesAudioThatSendsAgain() {
        val dir = tmp.newFolder()
        val first = DictationSession(dir, newRecognizer = { done -> Fake(done).also { sessions.add(it) } }, onResult = { results.add(it) },
            silenceMs = 0, newEncoder = ::PassThrough, limits = DictationSession.Limits(switchFromMs = 1000, switchByMs = 1000))
        first.begin(0)
        for (t in 0 until 15) first.audio(pcm(), t * 100L)
        // The process dies here, before the audio ends: no result, the files stay.
        val recorded = sessions.map { it.bytes.toByteArray() }
        sessions.clear()

        val again = DictationSession.recover(dir, newRecognizer = { done -> Fake(done).also { sessions.add(it) } }, onResult = { results.add(it) })!!
        assertTrue(results.isEmpty())
        assertTrue(again.retry())
        assertEquals(2, sessions.size)
        recorded.zip(sessions).forEach { (live, resent) -> assertArrayEquals(live, resent.bytes.toByteArray()) }
        assertEquals(listOf(Result.success(Heard("part0 part1"))), results)
    }

    @Test fun nothingToRecoverFromAnEmptyFolder() {
        assertEquals(null, DictationSession.recover(tmp.newFolder(), newRecognizer = { done -> Fake(done) }, onResult = {}))
    }

    @Test fun stopsAfterSilence() {
        val s = session(silenceMs = 30_000)
        s.begin(0)
        assertTrue(s.audio(pcm(), 0))
        assertTrue(s.audio(pcm(loud = false), 29_900))
        assertFalse(s.audio(pcm(loud = false), 30_000))
    }

    @Test fun aCommandInOneSegmentKeepsTheOthersAsDictation() {
        val edit = Heard("Dear Sam, thanks.", UserIntent.REWRITE, "make it polite")
        assertEquals(Heard("first part make it polite"), combine(listOf(Heard("first part"), edit)))
        assertEquals(Heard("make it polite", UserIntent.REWRITE, "make it polite"), combine(listOf(edit, Heard(""))))
    }

    @Test fun joinsWithSpacesWhereTheLanguageUsesThem() {
        assertEquals("Hello there. And then", joinTexts(listOf("Hello there.", "And then")))
        assertEquals("one, two", joinTexts(listOf("one", ", two")))
        assertEquals("line\nnext", joinTexts(listOf("line\n", "next")))
        assertEquals("a b", joinTexts(listOf("a", "", " ", "b")))
        assertEquals("今日は晴れ。明日は雨", joinTexts(listOf("今日は晴れ。", "明日は雨")))
    }
}
