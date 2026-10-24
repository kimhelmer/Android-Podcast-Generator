package com.podcaststudio.app.audio

import com.podcaststudio.app.domain.AssembledAudio
import com.podcaststudio.app.domain.AudioAssembler
import com.podcaststudio.app.domain.Language
import com.podcaststudio.app.domain.PodcastRequest
import com.podcaststudio.app.domain.PodcastScript
import com.podcaststudio.app.domain.ResearchProvider
import com.podcaststudio.app.domain.ResearchResult
import com.podcaststudio.app.domain.ScriptGenerator
import com.podcaststudio.app.domain.ScriptSegment
import com.podcaststudio.app.domain.Speaker
import com.podcaststudio.app.domain.SpeechClip
import com.podcaststudio.app.domain.VoiceProfile
import com.podcaststudio.app.domain.VoiceProvider
import kotlinx.coroutines.delay

// Offline implementations. None of these touch the network.

val MOCK_VOICES = listOf(
    VoiceProfile("mock-anna", "Anna", "Warm, conversational", setOf(Language.DANISH, Language.ENGLISH)),
    VoiceProfile("mock-mads", "Mads", "Calm, authoritative", setOf(Language.DANISH, Language.ENGLISH)),
    VoiceProfile("mock-sofie", "Sofie", "Bright, energetic", setOf(Language.DANISH)),
    VoiceProfile("mock-james", "James", "Deep, narrative", setOf(Language.ENGLISH)),
)

class MockResearchProvider(
    private val stepDelayMs: Long = 1200,
    private val clock: () -> Long = System::currentTimeMillis,
) : ResearchProvider {
    override suspend fun research(request: PodcastRequest): ResearchResult {
        delay(stepDelayMs)
        return ResearchResult(
            topic = request.topic.trim(),
            language = request.language,
            notes = "Simulated research notes about: ${request.topic.trim()}",
            sources = emptyList(),
            researchedAt = clock(),
            model = "mock",
            warnings = listOf("Simulated research: no web sources."),
        )
    }
}

class MockScriptGenerator(
    private val stepDelayMs: Long = 1200,
    private val clock: () -> Long = System::currentTimeMillis,
) : ScriptGenerator {
    override suspend fun generate(request: PodcastRequest, research: ResearchResult): PodcastScript {
        delay(stepDelayMs)
        val topic = request.topic.trim()
        val lines = listOf(
            Triple("Introduction", Speaker.HOST, "Welcome to the show. Today we are talking about $topic."),
            Triple("Introduction", Speaker.GUEST, "Thanks for having me. It is a fascinating subject."),
            Triple("Basics", Speaker.HOST, "Where should we start?"),
            Triple("Closing summary", Speaker.GUEST, "Let us start with the basics and build from there."),
        )
        val segments = lines.mapIndexed { i, (section, speaker, text) ->
            ScriptSegment("s%02d".format(i + 1), speaker, text, section = section)
        }
        return PodcastScript(
            title = topic.lines().first().take(60),
            summary = "Simulated script for testing.",
            language = request.language,
            segments = segments,
            sources = research.sources,
            researchedAt = research.researchedAt,
            generatedAt = clock(),
            researchModel = research.model,
            scriptModel = "mock",
            targetWords = 0,
            estimatedDurationSeconds = request.durationMinutes * 60,
            warnings = listOf("Simulated script."),
        )
    }
}

class MockVoiceProvider(private val stepDelayMs: Long = 400) : VoiceProvider {
    override suspend fun availableVoices(): List<VoiceProfile> = MOCK_VOICES

    override suspend fun synthesize(segment: ScriptSegment, voice: VoiceProfile, language: Language, draftId: String?): SpeechClip {
        delay(stepDelayMs)
        return SpeechClip("mock-clip://${voice.id}/${segment.id}")
    }
}

/** Always returns the bundled sample audio, whatever clips it is given. */
class MockAudioAssembler(
    private val sampleUri: String,
    private val stepDelayMs: Long = 1200,
) : AudioAssembler {
    override suspend fun assemble(clips: List<String>): AssembledAudio {
        delay(stepDelayMs)
        return AssembledAudio(sampleUri, durationMs = null)
    }
}
