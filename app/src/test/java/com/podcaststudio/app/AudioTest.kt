package com.podcaststudio.app

import com.podcaststudio.app.audio.AudioClipValidator
import com.podcaststudio.app.audio.Pcm
import com.podcaststudio.app.audio.PcmWavAssembler
import com.podcaststudio.app.audio.WavFileWriter
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.IOException
import java.net.URI
import kotlin.math.abs

class AudioTest {
    @get:Rule val tmp = TemporaryFolder()
    private val rate = 24_000

    private fun clip(pcm: ByteArray, name: String): String = File(tmp.root, name).also { it.writeBytes(pcm) }.absolutePath

    // ---- PCM helpers ----

    @Test fun durationMath() {
        assertEquals(1000L, Pcm.durationMs(48_000, rate))
        assertEquals(500L, Pcm.durationMs(pcmTone(500).size.toLong(), rate))
        assertEquals(0, Pcm.silence(0, rate).size)
        assertEquals(rate / 5 * 2, Pcm.silence(200, rate).size)
    }

    @Test fun trimRemovesLeadAndTailButKeepsPadding() {
        val raw = pcmTone(toneMs = 400, headMs = 600, tailMs = 700)
        val trimmed = Pcm.trimSilence(raw, rate, keepMs = 40)
        val ms = Pcm.durationMs(trimmed.size.toLong(), rate)
        assertTrue("trimmed to about 480 ms, was $ms", ms in 470..490) // 400 tone + 2 x 40 padding
        assertTrue(Pcm.peak(trimmed) > 7000) // the speech itself is untouched
    }

    @Test fun trimLeavesQuietClipAlone() {
        val quiet = ByteArray(4800)
        assertEquals(quiet.size, Pcm.trimSilence(quiet, rate).size)
    }

    // ---- completeness validation ----

    @Test fun validatorAcceptsPlausibleSpeech() {
        assertNull(AudioClipValidator.validate(pcmTone(2000), "one two three four", rate))
    }

    @Test fun validatorRejectsProblemClips() {
        val text = (1..20).joinToString(" ") { "word" } // needs at least 2 s
        assertTrue(AudioClipValidator.validate(ByteArray(0), text, rate)!!.contains("long"))
        assertTrue(AudioClipValidator.validate(ByteArray(7), "hi there", rate)!!.contains("odd"))
        assertTrue(AudioClipValidator.validate(pcmTone(800), text, rate)!!.contains("truncated"))
        assertTrue(AudioClipValidator.validate(ByteArray(48_000), "hello there", rate)!!.contains("silent"))
        assertTrue(AudioClipValidator.validate(pcmTone(60_000), "hello there", rate)!!.contains("too long"))
    }

    // ---- WAV container ----

    @Test fun wavHeaderMatchesTheRiffSpecAndFileLength() {
        val f = tmp.newFile("a.wav")
        val pcm = pcmTone(1000)
        WavFileWriter(f, rate).use { it.write(pcm) }

        val wav = parseWav(f) // throws if any size field disagrees with the file
        assertEquals(rate, wav.sampleRate)
        assertEquals(16, wav.bits)
        assertEquals(1, wav.channels)
        assertEquals(pcm.size / 2, wav.frames)
        assertEquals(1.0, wav.seconds, 0.0001)
        assertEquals(WavFileWriter.HEADER_BYTES + pcm.size.toLong(), f.length())
    }

    @Test fun strictParserRejectsATruncatedWav() {
        val f = tmp.newFile("cut.wav")
        WavFileWriter(f, rate).use { it.write(pcmTone(500)) }
        f.writeBytes(f.readBytes().copyOf(f.length().toInt() - 10)) // simulate a cut-off file
        try {
            parseWav(f)
            fail()
        } catch (_: IllegalArgumentException) {
        }
    }

    // ---- assembler ----

    private fun assembler(pauseMs: Int = 200) = PcmWavAssembler(File(tmp.root, "episodes"), rate, pauseMs)

    @Test fun clipsAreJoinedInOrderWithTrimmingAndPauses() {
        val a = clip(pcmTone(1000, headMs = 300, tailMs = 300, amplitude = 4000), "a.pcm")
        val b = clip(pcmTone(1000, headMs = 300, tailMs = 300, amplitude = 12000), "b.pcm")
        val c = clip(pcmTone(1000, headMs = 300, tailMs = 300, amplitude = 8000), "c.pcm")

        val out = runBlocking { assembler(pauseMs = 200).assemble(listOf(a, b, c)) }

        // 3 x (1000 + 2x40 padding) + 2 x 200 pause
        assertTrue(abs(out.durationMs!! - (3 * 1080L + 2 * 200L)) <= 5)
        val file = File(URI(out.uri))
        assertTrue(out.uri.startsWith("file:///"))
        assertTrue(file.isFile)

        // parse it with the strict WAV reader and check the loudness order: a (quiet), b (loud), c (medium)
        val parsed = parseWav(file)
        assertEquals(rate, parsed.sampleRate)
        val bytes = parsed.pcm
        val third = bytes.size / 3
        fun peakOf(from: Int, to: Int) = Pcm.peak(bytes.copyOfRange(from - from % 2, to - to % 2))
        assertTrue(peakOf(0, third) in 3000..4500)
        assertTrue(peakOf(third, 2 * third) in 11000..12500)
        assertTrue(peakOf(2 * third, bytes.size) in 7000..8500)
    }

    @Test fun gapBetweenTurnsIsSilentAndFixedLength() {
        val a = clip(pcmTone(500, amplitude = 9000), "a.pcm")
        val b = clip(pcmTone(500, amplitude = 9000), "b.pcm")
        val out = runBlocking { assembler(pauseMs = 150).assemble(listOf(a, b)) }
        val bytes = parseWav(File(URI(out.uri))).pcm

        // each clip is pure tone, so trimming removes nothing; the gap starts right after the first 500 ms
        val gapStart = (rate * 500 / 1000) * 2
        val gap = bytes.copyOfRange(gapStart, gapStart + (rate * 150 / 1000) * 2)
        assertEquals(0, Pcm.peak(gap))
        assertTrue(abs(out.durationMs!! - (500L + 150L + 500L)) <= 2)
    }

    @Test fun clipsAreLeftInPlaceSoAFailureAfterAssemblyNeverCostsARegeneration() {
        val a = clip(pcmTone(500), "a.pcm")
        runBlocking { assembler().assemble(listOf(a)) }
        assertTrue(File(a).exists()) // the coordinator removes them only after the episode is safely saved
    }

    @Test fun missingOrBrokenClipFailsAndLeavesNoPartialEpisode() {
        val good = clip(pcmTone(500), "good.pcm")
        val odd = clip(ByteArray(11), "odd.pcm")
        val dir = File(tmp.root, "episodes")

        for (clips in listOf(listOf(good, File(tmp.root, "nope.pcm").absolutePath), listOf(good, odd), emptyList())) {
            try {
                runBlocking { assembler().assemble(clips) }
                fail("expected IOException for $clips")
            } catch (_: IOException) {
            }
        }
        assertEquals(emptyList<String>(), dir.listFiles().orEmpty().map { it.name })
        assertTrue(File(good).exists()) // inputs are kept when assembly fails, so nothing paid for is lost
    }

    @Test fun twoEpisodesNeverShareAFile() {
        val one = runBlocking { assembler().assemble(listOf(clip(pcmTone(300), "1.pcm"))) }
        val two = runBlocking { assembler().assemble(listOf(clip(pcmTone(300), "2.pcm"))) }
        assertNotNull(one.uri)
        assertTrue(one.uri != two.uri)
        assertTrue(abs(one.durationMs!! - 300) < 2)
    }
}
