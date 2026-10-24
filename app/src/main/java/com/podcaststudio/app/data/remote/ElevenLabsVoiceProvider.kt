package com.podcaststudio.app.data.remote

import com.podcaststudio.app.audio.AudioClipValidator
import com.podcaststudio.app.domain.AiSettingsStore
import com.podcaststudio.app.domain.Language
import com.podcaststudio.app.domain.PodcastRequest
import com.podcaststudio.app.domain.ScriptSegment
import com.podcaststudio.app.domain.Speaker
import com.podcaststudio.app.domain.SpeechClip
import com.podcaststudio.app.domain.SpeechPlan
import com.podcaststudio.app.domain.TtsModels
import com.podcaststudio.app.domain.TtsText
import com.podcaststudio.app.domain.VoiceProfile
import com.podcaststudio.app.domain.VoiceProvider
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest

/**
 * ElevenLabs text-to-speech. Each segment is spoken in its own request with its assigned voice.
 *
 * Every accepted clip is kept under [draftsDir]/<draftId>/, named after the segment and a hash of the voice, model,
 * language and exact text. A later run of the same draft reuses a clip only if that name matches and the audio still
 * validates, so changing a voice or model never reuses stale audio, and nothing is paid for twice.
 */
class ElevenLabsVoiceProvider(
    private val client: ElevenLabsClient,
    private val settings: AiSettingsStore,
    private val draftsDir: File,
    private val clock: () -> Long = System::currentTimeMillis,
) : VoiceProvider {

    private var cache: List<ElVoice>? = null
    private var cachedAt = 0L

    override suspend fun availableVoices(): List<VoiceProfile> {
        val now = clock()
        val voices = cache?.takeIf { now - cachedAt < VOICE_CACHE_MS } ?: client.listVoices().also {
            cache = it
            cachedAt = now
        }
        val languages = TtsModels.capabilities(settings.get().ttsModel).languages
        return voices.sortedBy { it.name.lowercase() }.map {
            VoiceProfile(it.id, it.name, it.description, languages, it.previewUrl)
        }
    }

    override suspend fun plan(request: PodcastRequest, segments: List<ScriptSegment>, draftId: String?): SpeechPlan {
        val caps = TtsModels.capabilities(settings.get().ttsModel)
        var reusable = 0
        var chars = 0
        for (seg in segments) {
            val voice = voiceFor(request, seg)
            if (existingClip(draftId, seg, voice, request.language) != null) reusable++
            else chars += TtsText.prepare(seg, caps).length
        }
        return SpeechPlan(segments.size, reusable, chars)
    }

    override suspend fun checkBudget(request: PodcastRequest, segments: List<ScriptSegment>?, draftId: String?) {
        val s = settings.get()
        val chars = if (segments == null) s.estimatedCharacters(request.durationMinutes) else plan(request, segments, draftId).charactersToSend
        if (chars > s.maxTtsCharacters) {
            val kind = if (segments == null) "is estimated to need about" else "still needs"
            throw ElevenLabsException(
                ElevenLabsErrorKind.BUDGET,
                "This episode $kind $chars characters of speech but your limit is ${s.maxTtsCharacters}. " +
                    "Choose a shorter episode or raise the limit in Settings.",
            )
        }
    }

    override suspend fun synthesize(segment: ScriptSegment, voice: VoiceProfile, language: Language, draftId: String?): SpeechClip {
        existingClip(draftId, segment, voice, language)?.let { return SpeechClip(it.absolutePath, characters = 0, reused = true) }

        val s = settings.get()
        val caps = TtsModels.capabilities(s.ttsModel)
        val text = TtsText.prepare(segment, caps)
        if (text.length > caps.maxCharsPerRequest) {
            throw ElevenLabsException(
                ElevenLabsErrorKind.TEXT_TOO_LONG,
                "Segment ${segment.id} is too long for ${caps.id} (${text.length} of ${caps.maxCharsPerRequest} characters).",
            )
        }
        val body = buildJsonObject {
            put("text", text)
            put("model_id", caps.id)
            if (caps.supportsLanguageCode) put("language_code", language.code)
        }

        // One request only. If the audio looks wrong we stop rather than silently pay again; Retry audio is the
        // user's explicit decision to re-request just the missing segments.
        val pcm = client.synthesize(voice.id, body)
        AudioClipValidator.validate(pcm, segment.text, TTS_SAMPLE_RATE)?.let {
            throw ElevenLabsException(
                ElevenLabsErrorKind.INCOMPLETE_AUDIO,
                "ElevenLabs returned incomplete audio for segment ${segment.id}: $it. Use Retry audio to try again.",
            )
        }
        val target = clipFile(draftId, segment, voice, language)
        target.parentFile?.mkdirs()
        val part = File(target.path + ".part")
        part.writeBytes(pcm)
        try {
            Files.move(part.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING)
        } catch (e: java.io.IOException) {
            part.delete()
            throw ElevenLabsException(ElevenLabsErrorKind.BAD_REQUEST, "Could not save the audio for segment ${segment.id}.")
        }
        return SpeechClip(target.absolutePath, characters = text.length, reused = false)
    }

    override suspend fun discardDraft(draftId: String) {
        File(draftsDir, safe(draftId)).deleteRecursively()
    }

    /** Deletes saved drafts nobody has touched for [olderThanMs] (default a week). */
    fun purgeOldDrafts(olderThanMs: Long = 7 * 24 * 60 * 60 * 1000L) {
        val cutoff = clock() - olderThanMs
        draftsDir.listFiles()?.filter { it.isDirectory && newestChange(it) < cutoff }?.forEach { it.deleteRecursively() }
    }

    private fun newestChange(dir: File): Long = (dir.listFiles().orEmpty().map { it.lastModified() } + dir.lastModified()).max()

    private fun voiceFor(request: PodcastRequest, seg: ScriptSegment): VoiceProfile =
        (if (seg.speaker == Speaker.HOST) request.hostVoice else request.guestVoice)
            ?: throw ElevenLabsException(ElevenLabsErrorKind.BAD_REQUEST, "A voice is missing for ${seg.speaker}.")

    private fun clipFile(draftId: String?, seg: ScriptSegment, voice: VoiceProfile, language: Language): File {
        val caps = TtsModels.capabilities(settings.get().ttsModel)
        val identity = "${caps.id}|${voice.id}|${language.code}|${TtsText.prepare(seg, caps)}"
        val hash = MessageDigest.getInstance("SHA-256").digest(identity.toByteArray()).joinToString("") { "%02x".format(it) }.take(12)
        return File(File(draftsDir, safe(draftId ?: "adhoc")), "${safe(seg.id)}-$hash.pcm")
    }

    /** A saved clip for exactly this voice, model, language and text, if it is still complete and valid. */
    private fun existingClip(draftId: String?, seg: ScriptSegment, voice: VoiceProfile, language: Language): File? {
        if (draftId == null) return null
        val f = clipFile(draftId, seg, voice, language)
        if (!f.isFile) return null
        val ok = runCatching { AudioClipValidator.validate(f.readBytes(), seg.text, TTS_SAMPLE_RATE) == null }.getOrDefault(false)
        if (!ok) {
            f.delete() // damaged: drop it so it is regenerated
            return null
        }
        return f
    }

    /** Ids come from a model or database; never let them escape the drafts folder. */
    private fun safe(name: String) = name.replace(Regex("[^A-Za-z0-9_-]"), "_").take(80).ifEmpty { "_" }

    private companion object {
        const val VOICE_CACHE_MS = 2 * 60 * 1000L
    }
}
