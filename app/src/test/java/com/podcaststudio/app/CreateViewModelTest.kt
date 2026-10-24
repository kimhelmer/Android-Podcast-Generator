package com.podcaststudio.app

import com.podcaststudio.app.audio.MOCK_VOICES
import com.podcaststudio.app.domain.AiSettings
import com.podcaststudio.app.domain.GenerationCoordinator
import com.podcaststudio.app.domain.GenerationJob
import com.podcaststudio.app.domain.InMemoryAiSettingsStore
import com.podcaststudio.app.domain.Language
import com.podcaststudio.app.domain.PodcastRequest
import com.podcaststudio.app.domain.ScriptSegment
import com.podcaststudio.app.domain.SpeechClip
import com.podcaststudio.app.domain.SpeechPlan
import com.podcaststudio.app.domain.UnfinishedDraft
import com.podcaststudio.app.domain.VoiceProfile
import com.podcaststudio.app.domain.VoiceProvider
import com.podcaststudio.app.ui.create.CreateViewModel
import com.podcaststudio.app.ui.create.GenerateResult
import com.podcaststudio.app.ui.create.VoicePreview
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class CreateViewModelTest {
    private class FakePreview : VoicePreview {
        override var onFinished: (() -> Unit)? = null
        val played = mutableListOf<String>()
        var stops = 0
        var released = false
        override fun play(url: String) { played += url }
        override fun stop() { stops++ }
        override fun release() { released = true }
    }

    private class FakeCoordinator : GenerationCoordinator {
        override val job = MutableStateFlow<GenerationJob?>(null)
        val started = mutableListOf<PodcastRequest>()
        var accept = true
        var draft: UnfinishedDraft? = null
        var plan: SpeechPlan? = SpeechPlan(14, 5, 3_000)
        val retried = mutableListOf<String>()
        val discarded = mutableListOf<String>()
        override fun start(request: PodcastRequest): Boolean { if (accept) started += request; return accept }
        override suspend fun retryAudio(draftId: String): Boolean { retried += draftId; return accept }
        override suspend fun planRetry(draftId: String) = plan
        override suspend fun unfinishedDraft() = draft
        override suspend fun discardDraft(draftId: String) { discarded += draftId; draft = null }
        override fun cancel() {}
        override fun reset() {}
    }

    private class FakeVoices(var result: () -> List<VoiceProfile>) : VoiceProvider {
        var calls = 0
        override suspend fun availableVoices(): List<VoiceProfile> { calls++; return result() }
        override suspend fun synthesize(segment: ScriptSegment, voice: VoiceProfile, language: Language, draftId: String?): SpeechClip = error("unused")
    }

    private val both = setOf(Language.DANISH, Language.ENGLISH)
    private val rachel = VoiceProfile("r", "Rachel", "calm", both, previewUrl = "https://cdn.example.org/r.mp3")
    private val adam = VoiceProfile("a", "Adam", "deep", both, previewUrl = null)
    private val settings = InMemoryAiSettingsStore()
    private val preview = FakePreview()
    private val coordinator = FakeCoordinator()

    @Before fun setUp() = Dispatchers.setMain(UnconfinedTestDispatcher())

    @After fun tearDown() = Dispatchers.resetMain()

    private fun vm(voices: FakeVoices) = CreateViewModel(voices, coordinator, settings, preview)

    @Test fun voicesLoadAndFilterByLanguage() {
        val danishOnly = VoiceProfile("d", "Dagmar", "", setOf(Language.DANISH))
        val vm = vm(FakeVoices { listOf(rachel, danishOnly) })
        assertEquals(listOf("Rachel"), vm.state.value.voicesForLanguage.map { it.name }) // English by default
        vm.setLanguage(Language.DANISH)
        assertEquals(listOf("Rachel", "Dagmar"), vm.state.value.voicesForLanguage.map { it.name })
    }

    @Test fun loadFailureIsShownAndNoVoicesOffered() {
        val vm = vm(FakeVoices { throw IllegalStateException("Add your ElevenLabs API key in Settings first.") })
        assertEquals("Add your ElevenLabs API key in Settings first.", vm.state.value.voiceError)
        assertTrue(vm.state.value.allVoices.isEmpty())
        assertFalse(vm.state.value.loadingVoices)
    }

    @Test fun aFailedLoadIsRetriedWhenTheScreenResumes() {
        var fail = true
        val voices = FakeVoices { if (fail) error("no key") else listOf(rachel, adam) }
        val vm = vm(voices)
        assertEquals("no key", vm.state.value.voiceError)

        fail = false // the user added the key in Settings
        vm.refreshMode()
        assertNull(vm.state.value.voiceError)
        assertEquals(2, vm.state.value.allVoices.size)
    }

    @Test fun voicesAreNotReloadedOnResumeUnlessSettingsChanged() {
        val voices = FakeVoices { listOf(rachel, adam) }
        val vm = vm(voices)
        vm.refreshMode(); vm.refreshMode()
        assertEquals(1, voices.calls)

        settings.set(AiSettings(useLiveElevenLabs = true)) // voice source changed
        vm.refreshMode()
        assertEquals(2, voices.calls)
        settings.set(AiSettings(useLiveElevenLabs = true, ttsModel = "eleven_v3")) // model changes language support
        vm.refreshMode()
        assertEquals(3, voices.calls)
    }

    @Test fun resumingWhileVoicesAreStillLoadingDoesNotStartASecondRequest() {
        val gate = kotlinx.coroutines.CompletableDeferred<List<VoiceProfile>>()
        var calls = 0
        val slow = object : VoiceProvider {
            override suspend fun availableVoices(): List<VoiceProfile> { calls++; return gate.await() }
            override suspend fun synthesize(segment: ScriptSegment, voice: VoiceProfile, language: Language, draftId: String?): SpeechClip = error("unused")
        }
        val vm = CreateViewModel(slow, coordinator, settings, preview)
        assertTrue(vm.state.value.loadingVoices)
        vm.refreshMode() // screen resumed (and again) while the first request is in flight
        vm.refreshMode()
        assertEquals(1, calls)
        gate.complete(listOf(rachel))
        assertEquals(1, vm.state.value.allVoices.size)
        assertFalse(vm.state.value.loadingVoices)
    }

    @Test fun selectionsSurviveReloadOnlyIfTheVoiceStillExists() {
        var list = listOf(rachel, adam)
        val voices = FakeVoices { list }
        val vm = vm(voices)
        vm.setHost(rachel); vm.setGuest(adam)

        list = listOf(rachel)
        settings.set(AiSettings(useLiveElevenLabs = true))
        vm.refreshMode()
        assertEquals("Rachel", vm.state.value.host?.name)
        assertNull(vm.state.value.guest)
    }

    @Test fun previewPlaysTogglesAndIgnoresVoicesWithoutASample() {
        val vm = vm(FakeVoices { listOf(rachel, adam) })
        vm.togglePreview(adam)
        assertTrue(preview.played.isEmpty())

        vm.togglePreview(rachel)
        assertEquals(listOf("https://cdn.example.org/r.mp3"), preview.played)
        assertEquals("https://cdn.example.org/r.mp3", vm.state.value.previewingUrl)

        vm.togglePreview(rachel) // second tap stops
        assertEquals(1, preview.stops)
        assertNull(vm.state.value.previewingUrl)

        vm.togglePreview(rachel)
        preview.onFinished!!.invoke() // sample ended by itself
        assertNull(vm.state.value.previewingUrl)
    }

    @Test fun generatingStopsAnyPreviewAndStartsTheJob() {
        val vm = vm(FakeVoices { listOf(rachel, adam) })
        vm.setTopic("How honeybees communicate")
        vm.setHost(rachel); vm.setGuest(adam)
        vm.togglePreview(rachel)

        assertEquals(GenerateResult.STARTED, vm.generate())
        assertEquals(1, preview.stops) // the only stop() came from generating
        assertEquals(1, coordinator.started.size)
        assertEquals("r", coordinator.started[0].hostVoice!!.id)
        assertEquals("a", coordinator.started[0].guestVoice!!.id)
    }

    @Test fun invalidFormDoesNotStartAJob() {
        val vm = vm(FakeVoices { MOCK_VOICES })
        assertEquals(GenerateResult.INVALID, vm.generate())
        assertTrue(coordinator.started.isEmpty())
    }

    @Test fun modeNoteInputsFollowSettingsAndDuration() {
        val vm = vm(FakeVoices { MOCK_VOICES })
        assertFalse(vm.state.value.liveAudio)

        settings.set(AiSettings(useLiveOpenAi = true, useLiveElevenLabs = true, wordsPerMinute = 100))
        vm.refreshMode()
        assertTrue(vm.state.value.liveOpenAi && vm.state.value.liveAudio)
        assertEquals((5 * 100 * 6.5).toInt(), vm.state.value.estimatedChars)
        vm.setDuration(15)
        assertEquals((15 * 100 * 6.5).toInt(), vm.state.value.estimatedChars)
    }

    @Test fun previewPlayerIsReleasedWithTheScreen() {
        val vm = vm(FakeVoices { MOCK_VOICES })
        val m = CreateViewModel::class.java.getDeclaredMethod("onCleared").apply { isAccessible = true }
        m.invoke(vm)
        assertTrue(preview.released)
        assertNull(preview.onFinished)
    }

    // ---------------------------------------------------------------- approval before paid generation

    private fun filledForm(vm: CreateViewModel) {
        vm.setTopic("How honeybees communicate")
        vm.setHost(rachel); vm.setGuest(adam)
    }

    @Test fun simulatedRunStartsWithoutAskingBecauseItCostsNothing() {
        val vm = vm(FakeVoices { listOf(rachel, adam) })
        filledForm(vm)
        assertEquals(GenerateResult.STARTED, vm.generate())
        assertEquals(1, coordinator.started.size)
        assertNull(vm.state.value.approval)
    }

    @Test fun anyPaidServiceNeedsExplicitApprovalBeforeAnythingStarts() {
        val modes = listOf(
            AiSettings(useLiveOpenAi = true),
            AiSettings(useLiveElevenLabs = true),
            AiSettings(useLiveOpenAi = true, useLiveElevenLabs = true),
        )
        for (live in modes) {
            settings.set(live)
            coordinator.started.clear()
            val vm = vm(FakeVoices { listOf(rachel, adam) })
            filledForm(vm)

            assertEquals(GenerateResult.NEEDS_APPROVAL, vm.generate())
            assertTrue("nothing may start before approval: $live", coordinator.started.isEmpty())
            val a = vm.state.value.approval!!
            assertEquals(live.useLiveOpenAi, a.liveOpenAi)
            assertEquals(live.useLiveElevenLabs, a.liveAudio)
            assertEquals(live.estimatedCharacters(5), a.estimatedChars)
            assertEquals(live.maxTtsCharacters, a.maxChars)
        }
    }

    @Test fun decliningApprovalStartsNothing() {
        settings.set(AiSettings(useLiveOpenAi = true, useLiveElevenLabs = true))
        val vm = vm(FakeVoices { listOf(rachel, adam) })
        filledForm(vm)
        vm.generate()
        vm.dismissApproval()
        assertNull(vm.state.value.approval)
        assertTrue(coordinator.started.isEmpty())
    }

    @Test fun approvingStartsExactlyOneJob() {
        settings.set(AiSettings(useLiveOpenAi = true, useLiveElevenLabs = true))
        val vm = vm(FakeVoices { listOf(rachel, adam) })
        filledForm(vm)
        vm.generate()
        assertEquals(GenerateResult.STARTED, vm.approveAndStart())
        assertEquals(1, coordinator.started.size)
        assertNull(vm.state.value.approval)
    }

    @Test fun anInvalidFormNeverReachesTheApprovalDialog() {
        settings.set(AiSettings(useLiveOpenAi = true))
        val vm = vm(FakeVoices { listOf(rachel, adam) })
        assertEquals(GenerateResult.INVALID, vm.generate())
        assertNull(vm.state.value.approval)
    }

    @Test fun aBusyCoordinatorIsReportedNotIgnored() {
        coordinator.accept = false
        val vm = vm(FakeVoices { listOf(rachel, adam) })
        filledForm(vm)
        assertEquals(GenerateResult.BUSY, vm.generate())
        assertTrue(vm.state.value.message!!.contains("still running"))
    }

    // ---------------------------------------------------------------- unfinished episode banner

    private val draft by lazy {
        UnfinishedDraft("d1", "The Waggle Dance", PodcastRequest("topic about bees", Language.ENGLISH, 5, rachel, adam), 14)
    }

    @Test fun bannerAppearsOnlyWhenNoJobIsRunning() {
        coordinator.draft = draft
        val vm = vm(FakeVoices { listOf(rachel, adam) })
        assertEquals("d1", vm.state.value.draft?.id)

        coordinator.job.value = com.podcaststudio.app.domain.GenerationJob("j", draft.request) // a job is now active
        assertNull(vm.state.value.draft)
    }

    @Test fun retryNeedsApprovalThenResumesTheSavedDraft() {
        coordinator.draft = draft
        val vm = vm(FakeVoices { listOf(rachel, adam) })

        vm.askRetry()
        val approval = vm.state.value.retryApproval!!
        assertEquals(5, approval.plan.reusableSegments)
        assertTrue("nothing resumed before approval", coordinator.retried.isEmpty())

        var resumed = false
        vm.confirmRetry { resumed = true }
        assertTrue(resumed)
        assertEquals(listOf("d1"), coordinator.retried)
        assertNull(vm.state.value.retryApproval)
    }

    @Test fun aRefusedRetryExplainsInsteadOfNavigating() {
        coordinator.draft = draft
        val vm = vm(FakeVoices { listOf(rachel, adam) })
        vm.askRetry()
        coordinator.accept = false
        var resumed = false
        vm.confirmRetry { resumed = true }
        assertFalse(resumed)
        assertTrue(vm.state.value.message!!.contains("Could not resume"))
    }

    @Test fun decliningRetryResumesNothing() {
        coordinator.draft = draft
        val vm = vm(FakeVoices { listOf(rachel, adam) })
        vm.askRetry()
        vm.dismissRetry()
        assertNull(vm.state.value.retryApproval)
        assertTrue(coordinator.retried.isEmpty())
    }

    @Test fun anUnresumableDraftIsDroppedWithAnExplanation() {
        coordinator.draft = draft
        coordinator.plan = null
        val vm = vm(FakeVoices { listOf(rachel, adam) })
        vm.askRetry()
        assertNull(vm.state.value.draft)
        assertTrue(vm.state.value.message!!.contains("no longer"))
    }

    @Test fun discardRemovesTheDraft() {
        coordinator.draft = draft
        val vm = vm(FakeVoices { listOf(rachel, adam) })
        vm.discardDraft()
        assertEquals(listOf("d1"), coordinator.discarded)
        assertNull(vm.state.value.draft)
    }
}
