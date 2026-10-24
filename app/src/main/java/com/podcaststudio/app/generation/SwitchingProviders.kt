package com.podcaststudio.app.generation

import com.podcaststudio.app.data.remote.ElevenLabsErrorKind
import com.podcaststudio.app.data.remote.ElevenLabsException
import com.podcaststudio.app.data.remote.OpenAiErrorKind
import com.podcaststudio.app.data.remote.OpenAiException
import com.podcaststudio.app.domain.AiSettingsStore
import com.podcaststudio.app.domain.AssembledAudio
import com.podcaststudio.app.domain.AudioAssembler
import com.podcaststudio.app.domain.Language
import com.podcaststudio.app.domain.PodcastRequest
import com.podcaststudio.app.domain.PodcastScript
import com.podcaststudio.app.domain.ResearchProvider
import com.podcaststudio.app.domain.ResearchResult
import com.podcaststudio.app.domain.ScriptGenerator
import com.podcaststudio.app.domain.ScriptSegment
import com.podcaststudio.app.domain.SpeechClip
import com.podcaststudio.app.domain.SpeechPlan
import com.podcaststudio.app.domain.VoiceProfile
import com.podcaststudio.app.domain.VoiceProvider
import com.podcaststudio.app.security.SecretKeyName
import com.podcaststudio.app.security.SecretStore

/**
 * Picks the live OpenAI provider or the offline mock at call time, from the current settings.
 * The live provider is reached only when the user has switched live mode on and stored a key.
 */
class SwitchingResearchProvider(
    private val settings: AiSettingsStore,
    private val secrets: SecretStore,
    private val mock: ResearchProvider,
    private val live: ResearchProvider,
) : ResearchProvider {
    override suspend fun research(request: PodcastRequest): ResearchResult =
        if (useLive(settings, secrets)) live.research(request) else mock.research(request)
}

class SwitchingScriptGenerator(
    private val settings: AiSettingsStore,
    private val secrets: SecretStore,
    private val mock: ScriptGenerator,
    private val live: ScriptGenerator,
) : ScriptGenerator {
    override suspend fun generate(request: PodcastRequest, research: ResearchResult): PodcastScript =
        if (useLive(settings, secrets)) live.generate(request, research) else mock.generate(request, research)
}

class SwitchingVoiceProvider(
    private val settings: AiSettingsStore,
    private val secrets: SecretStore,
    private val mock: VoiceProvider,
    private val live: VoiceProvider,
) : VoiceProvider {
    private fun pick() = if (useLiveSpeech(settings, secrets)) live else mock

    override suspend fun availableVoices(): List<VoiceProfile> = pick().availableVoices()
    override suspend fun synthesize(segment: ScriptSegment, voice: VoiceProfile, language: Language, draftId: String?): SpeechClip =
        pick().synthesize(segment, voice, language, draftId)

    override suspend fun checkBudget(request: PodcastRequest, segments: List<ScriptSegment>?, draftId: String?) =
        pick().checkBudget(request, segments, draftId)

    override suspend fun plan(request: PodcastRequest, segments: List<ScriptSegment>, draftId: String?): SpeechPlan =
        pick().plan(request, segments, draftId)

    // Saved clips belong to the live provider whatever the current switch says, so always let it clean up.
    override suspend fun discardDraft(draftId: String) {
        live.discardDraft(draftId)
        mock.discardDraft(draftId)
    }
}

/**
 * Chooses by what it is given: any real clip file means the live assembler, which refuses simulated references. So a
 * job that already paid for real speech fails loudly if the switch is flipped mid-run, rather than quietly being
 * saved with the bundled sample audio.
 */
class SwitchingAudioAssembler(
    private val mock: AudioAssembler,
    private val live: AudioAssembler,
) : AudioAssembler {
    override suspend fun assemble(clips: List<String>): AssembledAudio {
        val real = clips.any { !it.startsWith("mock-clip://") }
        return if (real) live.assemble(clips) else mock.assemble(clips)
    }
}

private fun useLiveSpeech(settings: AiSettingsStore, secrets: SecretStore): Boolean {
    if (!settings.get().useLiveElevenLabs) return false
    if (!secrets.has(SecretKeyName.ELEVENLABS)) {
        throw ElevenLabsException(
            ElevenLabsErrorKind.NO_API_KEY,
            "Live ElevenLabs mode is on but no API key is stored. Add it in Settings.",
        )
    }
    return true
}

private fun useLive(settings: AiSettingsStore, secrets: SecretStore): Boolean {
    if (!settings.get().useLiveOpenAi) return false
    if (!secrets.has(SecretKeyName.OPENAI)) {
        throw OpenAiException(OpenAiErrorKind.NO_API_KEY, "Live OpenAI mode is on but no API key is stored. Add it in Settings.")
    }
    return true
}
