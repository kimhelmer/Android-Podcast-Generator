package com.podcaststudio.app.audio

import com.podcaststudio.app.domain.AssembledAudio
import com.podcaststudio.app.domain.AudioAssembler
import com.podcaststudio.app.domain.wordCount
import java.io.File
import java.io.IOException
import java.io.RandomAccessFile
import java.util.UUID
import kotlin.math.abs

/** 16-bit little-endian mono PCM helpers. */
object Pcm {
    const val BYTES_PER_SAMPLE = 2

    fun durationMs(byteCount: Long, sampleRate: Int): Long = byteCount * 1000 / (sampleRate.toLong() * BYTES_PER_SAMPLE)

    fun sample(pcm: ByteArray, index: Int): Int =
        ((pcm[index * 2 + 1].toInt() shl 8) or (pcm[index * 2].toInt() and 0xFF)) // sign-extended by the shl of a signed byte

    fun sampleCount(pcm: ByteArray) = pcm.size / BYTES_PER_SAMPLE

    fun silence(ms: Int, sampleRate: Int): ByteArray = ByteArray((sampleRate.toLong() * ms / 1000).toInt() * BYTES_PER_SAMPLE)

    /**
     * Cuts quiet lead-in and tail so speaker changes do not carry dead air, leaving [keepMs] of padding so
     * word onsets and decays are not clipped. All-quiet input is returned unchanged.
     */
    fun trimSilence(pcm: ByteArray, sampleRate: Int, threshold: Int = 300, keepMs: Int = 40): ByteArray {
        val n = sampleCount(pcm)
        var first = 0
        while (first < n && abs(sample(pcm, first)) <= threshold) first++
        if (first == n) return pcm
        var last = n - 1
        while (last > first && abs(sample(pcm, last)) <= threshold) last--
        val keep = sampleRate * keepMs / 1000
        val from = maxOf(0, first - keep)
        val to = minOf(n, last + 1 + keep)
        return pcm.copyOfRange(from * BYTES_PER_SAMPLE, to * BYTES_PER_SAMPLE)
    }

    fun peak(pcm: ByteArray): Int {
        var p = 0
        for (i in 0 until sampleCount(pcm)) p = maxOf(p, abs(sample(pcm, i)))
        return p
    }
}

/** Decides whether a synthesized clip looks complete enough to accept. */
object AudioClipValidator {
    private const val MIN_MS = 300L
    private const val MIN_MS_PER_WORD = 100L // real speech runs about 400 ms per word
    private const val MAX_MS_PER_WORD = 2_000L
    private const val MIN_PEAK = 100

    /** Returns a problem description, or null if the clip is acceptable. */
    fun validate(pcm: ByteArray, text: String, sampleRate: Int): String? {
        if (pcm.size % Pcm.BYTES_PER_SAMPLE != 0) return "audio has an odd number of bytes"
        val ms = Pcm.durationMs(pcm.size.toLong(), sampleRate)
        val words = text.wordCount()
        if (ms < MIN_MS) return "audio is only ${ms} ms long"
        if (ms < words * MIN_MS_PER_WORD) return "audio is ${ms} ms for $words words (looks truncated)"
        if (ms > words * MAX_MS_PER_WORD + 5_000) return "audio is ${ms} ms for $words words (too long)"
        if (Pcm.peak(pcm) < MIN_PEAK) return "audio is silent"
        return null
    }
}

/** Streams a PCM body into a canonical 44-byte-header WAV file. */
class WavFileWriter(private val file: File, private val sampleRate: Int) : AutoCloseable {
    private val raf = RandomAccessFile(file, "rw").also {
        it.setLength(0)
        it.write(ByteArray(HEADER_BYTES))
    }
    var dataBytes = 0L
        private set

    fun write(pcm: ByteArray) {
        raf.write(pcm)
        dataBytes += pcm.size
    }

    override fun close() {
        raf.seek(0)
        raf.write(header(dataBytes, sampleRate))
        raf.close()
    }

    companion object {
        const val HEADER_BYTES = 44

        fun header(dataBytes: Long, sampleRate: Int): ByteArray {
            val b = java.nio.ByteBuffer.allocate(HEADER_BYTES).order(java.nio.ByteOrder.LITTLE_ENDIAN)
            b.put("RIFF".toByteArray()).putInt((36 + dataBytes).toInt()).put("WAVE".toByteArray())
            b.put("fmt ".toByteArray()).putInt(16).putShort(1).putShort(1)
            b.putInt(sampleRate).putInt(sampleRate * Pcm.BYTES_PER_SAMPLE).putShort(Pcm.BYTES_PER_SAMPLE.toShort()).putShort(16)
            b.put("data".toByteArray()).putInt(dataBytes.toInt())
            return b.array()
        }
    }
}

/**
 * Joins per-segment PCM clips, in script order, into one WAV episode. Lead-in and tail silence are trimmed and
 * a short fixed pause is placed between turns, so the conversation flows without dead air.
 */
class PcmWavAssembler(
    private val outputDir: File,
    private val sampleRate: Int,
    private val pauseMs: Int = 180,
) : AudioAssembler {

    override suspend fun assemble(clips: List<String>): AssembledAudio {
        if (clips.isEmpty()) throw IOException("There is no audio to assemble.")
        outputDir.mkdirs()
        val name = "episode-${UUID.randomUUID()}"
        val part = File(outputDir, "$name.wav.part")
        val target = File(outputDir, "$name.wav")
        val gap = Pcm.silence(pauseMs, sampleRate)
        try {
            WavFileWriter(part, sampleRate).use { w ->
                clips.forEachIndexed { i, path ->
                    val f = File(path)
                    if (!f.isFile) throw IOException("Audio for segment ${i + 1} is missing.")
                    val pcm = f.readBytes()
                    if (pcm.isEmpty() || pcm.size % Pcm.BYTES_PER_SAMPLE != 0) {
                        throw IOException("Audio for segment ${i + 1} is incomplete.")
                    }
                    if (i > 0) w.write(gap)
                    w.write(Pcm.trimSilence(pcm, sampleRate))
                }
                if (w.dataBytes <= 0) throw IOException("Assembled audio is empty.")
            }
            if (!part.renameTo(target)) throw IOException("Could not save the episode audio.")
        } catch (e: Exception) {
            part.delete()
            target.delete()
            throw e
        }
        // The clips are left in place: the coordinator removes them once the episode itself is saved, so a failure
        // between assembly and saving never costs the user a paid regeneration.
        return AssembledAudio(fileUri(target), Pcm.durationMs(target.length() - WavFileWriter.HEADER_BYTES, sampleRate))
    }

    /** file:///path form, which Media3 and Android URIs expect. */
    private fun fileUri(f: File) = f.toURI().toString().replaceFirst("file:/", "file:///")
}
