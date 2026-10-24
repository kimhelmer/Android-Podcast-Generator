package com.podcaststudio.app

import com.podcaststudio.app.audio.MOCK_VOICES
import com.podcaststudio.app.audio.MockResearchProvider
import com.podcaststudio.app.audio.MockScriptGenerator
import com.podcaststudio.app.domain.AssembledAudio
import com.podcaststudio.app.domain.AudioAssembler
import com.podcaststudio.app.domain.GenerationStatus
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
import com.podcaststudio.app.generation.DefaultGenerationCoordinator
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Audio stage of the coordinator: ordering, progress, budget, resume, and the one-run-at-a-time rule. */
@OptIn(ExperimentalCoroutinesApi::class)
class GenerationAudioTest {
    private val request = PodcastRequest("A talk about bees", Language.ENGLISH, 5, MOCK_VOICES[0], MOCK_VOICES[1])

    /** Behaves like the live provider's clip store: a clip made for a draft is reused, never paid for twice. */
    private class StoreVoices(
        /** Fresh (paid) request numbers, counted from 1 over the whole test, that should fail. */
        private val failFreshCalls: MutableSet<Int> = mutableSetOf(),
        private val budgetError: Exception? = null,
    ) : VoiceProvider {
        val saved = mutableMapOf<Pair<String, String>, SpeechClip>() // (draftId, segmentId) -> clip
        val fresh = mutableListOf<Triple<String, String, Language>>() // paid requests: segment id, voice id, language
        val budgetChecks = mutableListOf<Pair<Boolean, String?>>() // (checked real script?, draftId)
        val discarded = mutableListOf<String>()
        var freshCount = 0

        override suspend fun availableVoices(): List<VoiceProfile> = MOCK_VOICES

        override suspend fun synthesize(segment: ScriptSegment, voice: VoiceProfile, language: Language, draftId: String?): SpeechClip {
            saved[draftId!! to segment.id]?.let { return it.copy(characters = 0, reused = true) }
            freshCount++
            if (freshCount in failFreshCalls) error("tts failed")
            delay(100)
            fresh += Triple(segment.id, voice.id, language)
            return SpeechClip("clip-${segment.id}", characters = segment.text.length).also { saved[draftId to segment.id] = it }
        }

        override suspend fun checkBudget(request: PodcastRequest, segments: List<ScriptSegment>?, draftId: String?) {
            budgetChecks += (segments != null) to draftId
            budgetError?.let { throw it }
        }

        override suspend fun plan(request: PodcastRequest, segments: List<ScriptSegment>, draftId: String?): SpeechPlan {
            val reusable = segments.count { saved.containsKey(draftId!! to it.id) }
            return SpeechPlan(segments.size, reusable, segments.filter { !saved.containsKey(draftId!! to it.id) }.sumOf { it.text.length })
        }

        override suspend fun discardDraft(draftId: String) {
            discarded += draftId
            saved.keys.removeAll { it.first == draftId }
        }
    }

    private class RecordingAssembler(private val durationMs: Long? = 125_000) : AudioAssembler {
        var assembled: List<String>? = null
        override suspend fun assemble(clips: List<String>): AssembledAudio {
            assembled = clips.toList()
            return AssembledAudio("file:///episode.wav", durationMs)
        }
    }

    private class CountingResearch : ResearchProvider {
        var calls = 0
        private val inner = MockResearchProvider(1000)
        override suspend fun research(request: PodcastRequest): ResearchResult { calls++; return inner.research(request) }
    }

    private class CountingScripts : ScriptGenerator {
        var calls = 0
        private val inner = MockScriptGenerator(1000)
        override suspend fun generate(request: PodcastRequest, research: ResearchResult): PodcastScript { calls++; return inner.generate(request, research) }
    }

    private class Fixture(
        scope: TestScope,
        val voices: StoreVoices = StoreVoices(),
        val assembler: RecordingAssembler = RecordingAssembler(),
        val repo: FakeRepo = FakeRepo(),
        val content: FakeContent = FakeContent(),
        val research: ResearchProvider = CountingResearch(),
        val scripts: CountingScripts = CountingScripts(),
    ) {
        val coordinator = DefaultGenerationCoordinator(scope, research, scripts, voices, assembler, repo, content, clock = { 7L })
    }

    // ---------------------------------------------------------------- normal run

    @Test fun eachSegmentIsSpokenInScriptOrderWithItsOwnVoiceAndLanguage() = runTest {
        val f = Fixture(this)
        f.coordinator.start(request)
        advanceUntilIdle()

        assertEquals(listOf("s01", "s02", "s03", "s04"), f.voices.fresh.map { it.first })
        assertEquals(
            listOf(MOCK_VOICES[0].id, MOCK_VOICES[1].id, MOCK_VOICES[0].id, MOCK_VOICES[1].id),
            f.voices.fresh.map { it.second }, // HOST, GUEST, HOST, GUEST
        )
        assertTrue(f.voices.fresh.all { it.third == Language.ENGLISH })
        assertEquals(listOf("clip-s01", "clip-s02", "clip-s03", "clip-s04"), f.assembler.assembled)
        assertEquals(GenerationStatus.COMPLETED, f.coordinator.job.value!!.status)
    }

    @Test fun progressCountsSegmentsAndLabelShowsIt() = runTest {
        val f = Fixture(this)
        f.coordinator.start(request)
        advanceTimeBy(2_000 + 250); runCurrent() // research + script done, two segments spoken
        val mid = f.coordinator.job.value!!
        assertEquals(GenerationStatus.GENERATING_AUDIO, mid.status)
        assertEquals("Generating voices (2/4)", mid.stageLabel)
        assertTrue(mid.hasScript)
        advanceUntilIdle()
        assertEquals(1f, f.coordinator.job.value!!.progress, 0f)
    }

    @Test fun episodeDurationAndUriComeFromTheAssembledAudio() = runTest {
        val f = Fixture(this, assembler = RecordingAssembler(durationMs = 125_400))
        f.coordinator.start(request)
        advanceUntilIdle()
        val ep = f.repo.items.value.single()
        assertEquals(125, ep.durationSeconds)
        assertEquals("file:///episode.wav", ep.audioUri)
    }

    @Test fun unknownAudioLengthFallsBackToTheScriptEstimate() = runTest {
        val f = Fixture(this, assembler = RecordingAssembler(durationMs = null))
        f.coordinator.start(request)
        advanceUntilIdle()
        assertEquals(f.content.script!!.estimatedDurationSeconds, f.repo.items.value.single().durationSeconds)
    }

    @Test fun successDiscardsTheDraftClipsOnlyAfterTheEpisodeIsSaved() = runTest {
        val f = Fixture(this)
        f.coordinator.start(request)
        advanceUntilIdle()
        val id = f.repo.items.value.single().id
        assertEquals(listOf(id), f.voices.discarded)
    }

    @Test fun actualCharactersAreRecordedAsTheyAreSent() = runTest {
        val f = Fixture(this)
        f.coordinator.start(request)
        advanceUntilIdle()
        assertEquals(f.content.script!!.segments.sumOf { it.text.length }, f.content.ttsCharacters)
    }

    // ---------------------------------------------------------------- budget

    @Test fun overBudgetIsRefusedBeforeResearchSoNothingIsSpent() = runTest {
        val research = CountingResearch()
        val f = Fixture(this, voices = StoreVoices(budgetError = IllegalStateException("over the character limit")), research = research)
        f.coordinator.start(request)
        advanceUntilIdle()

        val job = f.coordinator.job.value!!
        assertEquals(GenerationStatus.FAILED, job.status)
        assertEquals("over the character limit", job.error)
        assertEquals(0, research.calls)
        assertEquals(listOf(false to null), f.voices.budgetChecks) // only the estimate ran
    }

    @Test fun budgetIsCheckedAgainstTheRealScriptForTheDraftBeforeAnySpeech() = runTest {
        val f = Fixture(this)
        f.coordinator.start(request)
        advanceUntilIdle()
        val id = f.repo.items.value.single().id
        assertEquals(listOf(false to null, true to id), f.voices.budgetChecks)
    }

    @Test fun scriptOverBudgetFailsBeforeSpeakingButKeepsEverythingPaidFor() = runTest {
        val voices = object : VoiceProvider by StoreVoices() {
            override suspend fun checkBudget(request: PodcastRequest, segments: List<ScriptSegment>?, draftId: String?) {
                if (segments != null) error("script too long for the limit")
            }
        }
        val f = Fixture(this, voices = StoreVoices())
        val coordinator = DefaultGenerationCoordinator(this, f.research, f.scripts, voices, f.assembler, f.repo, f.content, clock = { 7L })
        coordinator.start(request)
        advanceUntilIdle()
        val job = coordinator.job.value!!
        assertEquals(GenerationStatus.FAILED, job.status)
        assertTrue(job.hasScript) // so Retry audio is offered once the limit is raised
        assertEquals(listOf("request", "research", "script"), f.content.events)
    }

    // ---------------------------------------------------------------- failure keeps work; resume reuses it

    @Test fun speechFailureKeepsSavedClipsAndCreatesNoEpisode() = runTest {
        val f = Fixture(this, voices = StoreVoices(failFreshCalls = mutableSetOf(3)))
        f.coordinator.start(request)
        advanceUntilIdle()

        val job = f.coordinator.job.value!!
        assertEquals(GenerationStatus.FAILED, job.status)
        assertEquals("tts failed", job.error)
        assertTrue(job.hasScript)
        assertEquals(2, f.voices.saved.size) // s01, s02 survive
        assertTrue(f.voices.discarded.isEmpty())
        assertTrue(f.repo.items.value.isEmpty())
        assertNull(f.assembler.assembled)
    }

    @Test fun retryResumesFromTheFirstMissingSegmentWithoutRepeatingOpenAiWork() = runTest {
        val research = CountingResearch()
        val f = Fixture(this, voices = StoreVoices(failFreshCalls = mutableSetOf(3)), research = research)
        f.coordinator.start(request)
        advanceUntilIdle()
        val id = f.coordinator.job.value!!.id
        assertEquals(1, research.calls)
        assertEquals(1, f.scripts.calls)

        assertTrue(f.coordinator.retryAudio(id))
        advanceUntilIdle()

        val job = f.coordinator.job.value!!
        assertEquals(GenerationStatus.COMPLETED, job.status)
        assertEquals(1, research.calls) // research not repeated
        assertEquals(1, f.scripts.calls) // script not repeated
        assertEquals(listOf("s01", "s02", "s03", "s04"), f.voices.fresh.map { it.first }.sorted()) // each paid exactly once
        assertEquals(2, job.audioReused) // s01 and s02 reused
        assertEquals(listOf("clip-s01", "clip-s02", "clip-s03", "clip-s04"), f.assembler.assembled)
        assertEquals(1, f.repo.items.value.size)
        assertEquals(listOf(id), f.voices.discarded)
        assertEquals(f.content.script!!.segments.sumOf { it.text.length }, f.content.ttsCharacters) // no double counting
    }

    @Test fun retryUsesTheSavedVoicesAndLanguage() = runTest {
        val f = Fixture(this, voices = StoreVoices(failFreshCalls = mutableSetOf(2)))
        f.coordinator.start(request)
        advanceUntilIdle()
        f.coordinator.retryAudio(f.coordinator.job.value!!.id)
        advanceUntilIdle()
        assertEquals(listOf(MOCK_VOICES[0].id, MOCK_VOICES[1].id, MOCK_VOICES[0].id, MOCK_VOICES[1].id), f.voices.fresh.sortedBy { it.first }.map { it.second })
    }

    @Test fun cancellingDuringSpeechKeepsClipsAndTheDraftCanBeResumed() = runTest {
        val f = Fixture(this)
        f.coordinator.start(request)
        advanceTimeBy(2_000 + 250); runCurrent()
        f.coordinator.cancel()
        advanceUntilIdle()

        val job = f.coordinator.job.value!!
        assertEquals(GenerationStatus.CANCELLED, job.status)
        assertTrue(job.hasScript)
        assertEquals(2, f.voices.saved.size)
        assertTrue(f.repo.items.value.isEmpty())

        assertTrue(f.coordinator.retryAudio(job.id))
        advanceUntilIdle()
        assertEquals(GenerationStatus.COMPLETED, f.coordinator.job.value!!.status)
        assertEquals(4, f.voices.fresh.size) // each segment paid for once overall
    }

    @Test fun planRetryReportsWhatWouldBeSent() = runTest {
        val f = Fixture(this, voices = StoreVoices(failFreshCalls = mutableSetOf(3)))
        f.coordinator.start(request)
        advanceUntilIdle()
        val plan = f.coordinator.planRetry(f.coordinator.job.value!!.id)!!
        assertEquals(4, plan.totalSegments)
        assertEquals(2, plan.reusableSegments)
        assertEquals(2, plan.remainingSegments)
        assertEquals(f.content.script!!.segments.drop(2).sumOf { it.text.length }, plan.charactersToSend)
    }

    @Test fun retryIsRefusedWhenThereIsNothingToResumeOrAJobIsRunning() = runTest {
        val f = Fixture(this)
        assertFalse(f.coordinator.retryAudio("unknown"))
        assertNull(f.coordinator.planRetry("unknown"))

        f.coordinator.start(request)
        advanceTimeBy(2_150); runCurrent() // mid-speech
        assertFalse(f.coordinator.retryAudio(f.coordinator.job.value!!.id)) // running
        advanceUntilIdle()
        assertFalse(f.coordinator.retryAudio(f.coordinator.job.value!!.id)) // already finished: no duplicate episode
        assertEquals(1, f.repo.items.value.size)
    }

    @Test fun unfinishedDraftIsOfferedOnlyWhileTheAudioIsUnfinished() = runTest {
        val f = Fixture(this, voices = StoreVoices(failFreshCalls = mutableSetOf(2)))
        assertNull(f.coordinator.unfinishedDraft())
        f.coordinator.start(request)
        advanceUntilIdle()
        val draft = f.coordinator.unfinishedDraft()
        assertNotNull(draft)
        assertEquals(4, draft!!.segmentCount)
        assertEquals(request, draft.request)
    }

    @Test fun discardingADraftRemovesItsContentAndClips() = runTest {
        val f = Fixture(this, voices = StoreVoices(failFreshCalls = mutableSetOf(2)))
        f.coordinator.start(request)
        advanceUntilIdle()
        val id = f.coordinator.job.value!!.id

        f.coordinator.discardDraft(id)

        assertEquals(listOf(id), f.voices.discarded)
        assertTrue("delete" in f.content.events)
        assertNull(f.coordinator.job.value) // the failed job that pointed at it is cleared
    }

    @Test fun aRunningJobCannotBeDiscarded() = runTest {
        val f = Fixture(this)
        f.coordinator.start(request)
        advanceTimeBy(2_150); runCurrent() // mid-speech
        f.coordinator.discardDraft(f.coordinator.job.value!!.id)
        assertTrue(f.voices.discarded.isEmpty())
        advanceUntilIdle()
        assertEquals(GenerationStatus.COMPLETED, f.coordinator.job.value!!.status)
    }

    // ---------------------------------------------------------------- one run at a time

    @Test fun startIsRefusedWhileAJobIsRunning() = runTest {
        val f = Fixture(this)
        assertTrue(f.coordinator.start(request))
        val firstId = f.coordinator.job.value!!.id
        assertFalse(f.coordinator.start(request.copy(topic = "Something else entirely")))
        assertEquals(firstId, f.coordinator.job.value!!.id)
        advanceUntilIdle()
        assertEquals(1, f.repo.items.value.size)
    }

    /** A research step that ignores cancellation for a while, like a request already in flight. */
    private class StubbornResearch : ResearchProvider {
        var finished = false
        override suspend fun research(request: PodcastRequest): ResearchResult {
            withContext(NonCancellable) { delay(1_000) }
            finished = true
            return MockResearchProvider(0).research(request)
        }
    }

    @Test fun aCancelledRunStillUnwindingBlocksANewStartSoTheyCannotOverlap() = runTest {
        val stubborn = StubbornResearch()
        val f = Fixture(this, research = stubborn)
        f.coordinator.start(request)
        runCurrent()
        f.coordinator.cancel()
        assertEquals(GenerationStatus.CANCELLED, f.coordinator.job.value!!.status)

        assertFalse("old run is still unwinding", f.coordinator.start(request))
        advanceUntilIdle()
        assertTrue(stubborn.finished)
        assertEquals(0, f.scripts.calls) // the cancelled run never went on to the next paid step
        assertTrue(f.coordinator.start(request)) // now it may start
    }

    @Test fun aStaleRunCannotTouchStateAfterTheJobWasCleared() = runTest {
        val stubborn = StubbornResearch()
        val f = Fixture(this, research = stubborn)
        f.coordinator.start(request)
        runCurrent()
        f.coordinator.cancel()
        f.coordinator.reset() // the user pressed Back
        assertNull(f.coordinator.job.value)

        advanceUntilIdle() // the old run finishes its in-flight step; nothing may crash or resurrect the job
        assertNull(f.coordinator.job.value)
        assertEquals(0, f.scripts.calls)
        assertTrue(f.repo.items.value.isEmpty())
    }

    @Test fun invalidRequestFailsImmediately() = runTest {
        val f = Fixture(this)
        assertTrue(f.coordinator.start(request.copy(topic = "")))
        assertEquals(GenerationStatus.FAILED, f.coordinator.job.value!!.status)
    }
}
