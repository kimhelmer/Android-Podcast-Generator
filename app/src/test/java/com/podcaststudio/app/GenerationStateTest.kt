package com.podcaststudio.app

import com.podcaststudio.app.audio.MOCK_VOICES
import com.podcaststudio.app.audio.MockAudioAssembler
import com.podcaststudio.app.audio.MockResearchProvider
import com.podcaststudio.app.audio.MockScriptGenerator
import com.podcaststudio.app.audio.MockVoiceProvider
import com.podcaststudio.app.domain.ContentRepository
import com.podcaststudio.app.domain.EpisodeRepository
import com.podcaststudio.app.domain.PodcastScript
import com.podcaststudio.app.domain.ResearchResult
import com.podcaststudio.app.domain.ScriptGenerator
import com.podcaststudio.app.domain.UnfinishedDraft
import com.podcaststudio.app.domain.GenerationJob
import com.podcaststudio.app.domain.GenerationStatus
import com.podcaststudio.app.domain.GenerationStatus.*
import com.podcaststudio.app.domain.Language
import com.podcaststudio.app.domain.PodcastEpisode
import com.podcaststudio.app.domain.PodcastRequest
import com.podcaststudio.app.domain.ResearchProvider
import com.podcaststudio.app.generation.DefaultGenerationCoordinator
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class FakeRepo : EpisodeRepository {
    val items = MutableStateFlow<List<PodcastEpisode>>(emptyList())
    override fun observeEpisodes(): Flow<List<PodcastEpisode>> = items
    override suspend fun getEpisode(id: String) = items.value.firstOrNull { it.id == id }
    override suspend fun save(episode: PodcastEpisode) { items.value = items.value + episode }
    override suspend fun updatePosition(id: String, positionMs: Long) {}
    override suspend fun delete(id: String) {}
}

/** Records what was saved, in order, so tests can check persistence timing. */
class FakeContent : ContentRepository {
    val events = mutableListOf<String>()
    var research: ResearchResult? = null
    var script: PodcastScript? = null
    override suspend fun saveResearch(episodeId: String, research: ResearchResult) { events += "research"; this.research = research }
    override suspend fun saveScript(episodeId: String, script: PodcastScript) { events += "script"; this.script = script }
    override suspend fun getResearch(episodeId: String) = research
    override suspend fun getScript(episodeId: String) = script
    override suspend fun delete(episodeId: String) { events += "delete" }

    var request: PodcastRequest? = null
    var ttsCharacters = 0
    override suspend fun saveRequest(episodeId: String, request: PodcastRequest) { events += "request"; this.request = request }
    override suspend fun getRequest(episodeId: String) = request
    override suspend fun purgeUnfinishedBefore(beforeMs: Long) { events += "purge" }
    override suspend fun addTtsCharacters(episodeId: String, characters: Int) { ttsCharacters += characters }
    override suspend fun getTtsCharacters(episodeId: String) = ttsCharacters
    override suspend fun latestUnfinished(sinceMs: Long): UnfinishedDraft? =
        script?.let { s -> request?.let { r -> UnfinishedDraft("draft", s.title, r, s.segments.size) } }
}

@OptIn(ExperimentalCoroutinesApi::class)
class GenerationStateTest {
    private val request = PodcastRequest("A talk about bees", Language.ENGLISH, 5, MOCK_VOICES[0], MOCK_VOICES[1])
    private val content = FakeContent()

    private fun coordinator(
        scope: TestScope,
        repo: FakeRepo,
        research: ResearchProvider = MockResearchProvider(1000),
        scripts: ScriptGenerator = MockScriptGenerator(1000),
    ) = DefaultGenerationCoordinator(
        scope = scope,
        research = research,
        scripts = scripts,
        voices = MockVoiceProvider(100),
        assembler = MockAudioAssembler("android.resource://test/1", 1000),
        episodes = repo,
        content = content,
        clock = { 42L },
    )

    // ---- pure state machine ----

    @Test fun activeStagesAdvanceInOrder() {
        val order = listOf(QUEUED, RESEARCHING, SCRIPTING, GENERATING_AUDIO, ASSEMBLING, COMPLETED)
        order.zipWithNext().forEach { (a, b) -> assertTrue("$a -> $b", a.canTransitionTo(b)) }
    }

    @Test fun stagesCannotBeSkippedOrReversed() {
        assertFalse(QUEUED.canTransitionTo(SCRIPTING))
        assertFalse(RESEARCHING.canTransitionTo(ASSEMBLING))
        assertFalse(SCRIPTING.canTransitionTo(RESEARCHING))
        assertFalse(ASSEMBLING.canTransitionTo(GENERATING_AUDIO))
    }

    @Test fun anyActiveStageCanFailOrCancel() {
        listOf(QUEUED, RESEARCHING, SCRIPTING, GENERATING_AUDIO, ASSEMBLING).forEach {
            assertTrue(it.canTransitionTo(FAILED))
            assertTrue(it.canTransitionTo(CANCELLED))
        }
    }

    @Test fun terminalStatesAreFinal() {
        listOf(COMPLETED, FAILED, CANCELLED).forEach { t ->
            assertTrue(t.isTerminal)
            GenerationStatus.entries.forEach { assertFalse("$t -> $it", t.canTransitionTo(it)) }
        }
    }

    @Test fun jobRejectsIllegalTransition() {
        val job = GenerationJob("j", request)
        try {
            job.transitionTo(COMPLETED)
            fail("expected IllegalArgumentException")
        } catch (_: IllegalArgumentException) {
        }
    }

    @Test fun progressIsMonotonicAcrossActiveStages() {
        val values = listOf(QUEUED, RESEARCHING, SCRIPTING, GENERATING_AUDIO, ASSEMBLING, COMPLETED)
            .map { GenerationJob("j", request, it).progress }
        assertEquals(values.sorted(), values)
        assertEquals(1f, values.last(), 0f)
    }

    // ---- coordinator ----

    @Test fun happyPathWalksAllStagesAndSavesEpisode() = runTest {
        val repo = FakeRepo()
        val c = coordinator(this, repo)
        c.start(request)
        runCurrent()
        assertEquals(RESEARCHING, c.job.value!!.status)
        advanceTimeBy(1001); runCurrent()
        assertEquals(SCRIPTING, c.job.value!!.status)
        advanceTimeBy(1001); runCurrent()
        assertEquals(GENERATING_AUDIO, c.job.value!!.status)
        advanceTimeBy(401); runCurrent()
        assertEquals(ASSEMBLING, c.job.value!!.status)
        advanceUntilIdle()
        val done = c.job.value!!
        assertEquals(COMPLETED, done.status)
        assertNotNull(done.episodeId)
        val saved = repo.items.value.single()
        assertEquals(done.episodeId, saved.id)
        assertEquals(300, saved.durationSeconds)
        assertEquals("android.resource://test/1", saved.audioUri)
    }

    @Test fun cancellationStopsPipelineAndSavesNothing() = runTest {
        val repo = FakeRepo()
        val c = coordinator(this, repo)
        c.start(request)
        advanceTimeBy(1500); runCurrent()
        assertEquals(SCRIPTING, c.job.value!!.status)
        c.cancel()
        advanceUntilIdle()
        assertEquals(CANCELLED, c.job.value!!.status)
        assertTrue(repo.items.value.isEmpty())
    }

    @Test fun providerFailureMovesToFailedWithMessage() = runTest {
        val repo = FakeRepo()
        val boom = object : ResearchProvider {
            override suspend fun research(request: PodcastRequest): ResearchResult = error("quota exceeded")
        }
        val c = coordinator(this, repo, boom)
        c.start(request)
        advanceUntilIdle()
        val j = c.job.value!!
        assertEquals(FAILED, j.status)
        assertEquals("quota exceeded", j.error)
        assertTrue(repo.items.value.isEmpty())
    }

    @Test fun researchIsPersistedBeforeScriptingStartsAndScriptBeforeAudio() = runTest {
        val seenAtScripting = mutableListOf<String>()
        val spying = object : ScriptGenerator {
            private val inner = MockScriptGenerator(1000)
            override suspend fun generate(request: PodcastRequest, research: ResearchResult): PodcastScript {
                seenAtScripting += content.events // snapshot of what is stored when scripting begins
                return inner.generate(request, research)
            }
        }
        val c = coordinator(this, FakeRepo(), scripts = spying)
        c.start(request)
        advanceUntilIdle()
        assertEquals(listOf("request", "research"), seenAtScripting)
        assertEquals(listOf("request", "research", "script"), content.events)
        assertEquals(COMPLETED, c.job.value!!.status)
    }

    @Test fun researchSurvivesScriptFailure() = runTest {
        val failing = object : ScriptGenerator {
            override suspend fun generate(request: PodcastRequest, research: ResearchResult): PodcastScript =
                throw IllegalStateException("script failed")
        }
        val repo = FakeRepo()
        val c = coordinator(this, repo, scripts = failing)
        c.start(request)
        advanceUntilIdle()
        assertEquals(FAILED, c.job.value!!.status)
        assertEquals(listOf("request", "research"), content.events) // research kept for the user, no episode created
        assertTrue(repo.items.value.isEmpty())
    }

    @Test fun episodeUsesScriptTitleAndEstimatedDuration() = runTest {
        val repo = FakeRepo()
        val c = coordinator(this, repo)
        c.start(request)
        advanceUntilIdle()
        val ep = repo.items.value.single()
        assertEquals(content.script!!.title, ep.title)
        assertEquals(content.script!!.estimatedDurationSeconds, ep.durationSeconds)
    }

    @Test fun invalidRequestFailsImmediately() = runTest {
        val c = coordinator(this, FakeRepo())
        c.start(request.copy(topic = ""))
        assertEquals(FAILED, c.job.value!!.status)
    }

    @Test fun secondStartIgnoredWhileRunning() = runTest {
        val repo = FakeRepo()
        val c = coordinator(this, repo)
        c.start(request)
        val firstId = c.job.value!!.id
        c.start(request.copy(topic = "Something else entirely"))
        assertEquals(firstId, c.job.value!!.id)
        advanceUntilIdle()
        assertEquals(1, repo.items.value.size)
    }

    @Test fun resetClearsOnlyFinishedJobs() = runTest {
        val c = coordinator(this, FakeRepo())
        c.start(request)
        runCurrent()
        c.reset()
        assertNotNull(c.job.value)
        advanceUntilIdle()
        c.reset()
        assertNull(c.job.value)
    }
}
