package com.podcaststudio.app

import android.content.Context
import android.os.Looper
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.media3.exoplayer.ExoPlayer
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.podcaststudio.app.data.local.AppDatabase
import com.podcaststudio.app.domain.AiSettings
import com.podcaststudio.app.domain.GenerationStatus
import com.podcaststudio.app.domain.InMemoryAiSettingsStore
import com.podcaststudio.app.domain.Language
import com.podcaststudio.app.domain.PodcastEpisode
import com.podcaststudio.app.security.InMemorySecretStore
import com.podcaststudio.app.security.SecretKeyName
import com.podcaststudio.app.ui.PodcastStudioRoot
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import okio.Buffer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.io.File

/** Fake OpenAI + ElevenLabs on localhost, with counters and one switchable failure. */
class FakeServers {
    val openAi = MockWebServer()
    val eleven = MockWebServer()
    val ttsTexts: MutableList<String> = java.util.Collections.synchronizedList(mutableListOf())
    val failTtsRequestNumber = java.util.concurrent.atomic.AtomicInteger(-1)
    @Volatile var openAiStatus = 200

    init {
        openAi.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val body = request.body.readUtf8()
                if (openAiStatus != 200) return MockResponse().setResponseCode(openAiStatus).setBody("{}")
                val hasTools = Json.parseToJsonElement(body).jsonObject.containsKey("tools")
                return MockResponse().setBody(
                    if (hasTools) responsesJson("Bees dance. [verified]", listOf("Bee study" to "https://example.org/bees"))
                    else responsesJson(scriptJson()),
                )
            }
        }
        eleven.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                if (request.path!!.startsWith("/v2/voices")) {
                    return MockResponse().setBody(voicesPageJson(Triple("voiceHost", "Rachel", null), Triple("voiceGuest", "Adam", null)))
                }
                val text = Json.parseToJsonElement(request.body.readUtf8()).jsonObject["text"]!!.jsonPrimitive.content
                ttsTexts += text
                if (ttsTexts.size == failTtsRequestNumber.get()) return MockResponse().setResponseCode(503)
                val words = text.trim().split(Regex("\\s+")).size
                return MockResponse().setHeader("Content-Type", "audio/pcm")
                    .setBody(Buffer().write(pcmTone(toneMs = words * 130 + 400, headMs = 100, tailMs = 100)))
            }
        }
        openAi.start()
        eleven.start()
    }

    fun shutdown() {
        openAi.shutdown()
        eleven.shutdown()
    }
}

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w411dp-h891dp-xxhdpi")
class PaidFlowUiTest {
    @get:Rule val compose = createComposeRule()
    @get:Rule val tmp = TemporaryFolder()

    private val servers = FakeServers()
    private lateinit var db: AppDatabase
    private lateinit var container: AppContainer
    private val secrets = InMemorySecretStore()
    private val settings = InMemoryAiSettingsStore()

    private fun newContainer(): AppContainer {
        val ctx = ApplicationProvider.getApplicationContext<Context>()
        return AppContainer(
            ctx, db, secrets, settings,
            stepDelayMs = 20,
            openAiBaseUrl = servers.openAi.url("/").toString(),
            requireHttps = false,
            elevenLabsBaseUrl = servers.eleven.url("/").toString(),
            episodeAudioDir = File(tmp.root, "episodes"),
            draftsDir = File(tmp.root, "drafts"),
            pauseBetweenTurnsMs = 100,
            playerConnector = { ExoPlayer.Builder(ctx).build() },
        )
    }

    @Before fun setUp() {
        db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext<Context>(), AppDatabase::class.java)
            .allowMainThreadQueries().build()
        container = newContainer()
    }

    @After fun tearDown() {
        servers.shutdown()
        db.close()
    }

    private fun live(openAi: Boolean = true, speech: Boolean = true) {
        secrets.put(SecretKeyName.OPENAI, FAKE_KEY)
        secrets.put(SecretKeyName.ELEVENLABS, FAKE_EL_KEY)
        settings.set(AiSettings(useLiveOpenAi = openAi, useLiveElevenLabs = speech))
    }

    private fun show(c: AppContainer = container) = compose.setContent { PodcastStudioRoot(c) }

    private fun awaitUi(timeoutMs: Long = 20_000, condition: () -> Boolean) {
        val end = System.currentTimeMillis() + timeoutMs
        while (!condition()) {
            check(System.currentTimeMillis() < end) { "condition not met within $timeoutMs ms" }
            shadowOf(Looper.getMainLooper()).idle()
            compose.waitForIdle()
            Thread.sleep(20)
        }
    }

    private fun present(tag: String) = compose.onAllNodes(hasTestTag(tag), useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty()
    private fun presentText(t: String) = compose.onAllNodes(hasText(t, substring = true)).fetchSemanticsNodes().isNotEmpty()

    /** Fills the Create form with the two live voices. */
    private fun fillLiveForm() {
        awaitUi { servers.eleven.requestCount >= 1 }
        awaitUi { !presentText("Loading voices") }
        compose.onNodeWithTag("topicField").performTextInput("How honeybees communicate")
        compose.onNodeWithTag("hostVoice").performClick()
        compose.onNodeWithText("Rachel - british, female, narration").performClick()
        compose.onNodeWithTag("guestVoice").performClick()
        compose.onNodeWithText("Adam - british, female, narration").performClick()
    }

    // ---------------------------------------------------------------- approval

    @Test fun aSimulatedRunNeedsNoApprovalBecauseItCostsNothing() {
        show()
        compose.onNodeWithTag("topicField").performTextInput("How honeybees communicate")
        compose.onNodeWithTag("hostVoice").performClick()
        compose.onNodeWithText("Anna - Warm, conversational").performClick()
        compose.onNodeWithTag("guestVoice").performClick()
        compose.onNodeWithText("Mads - Calm, authoritative").performClick()
        compose.onNodeWithTag("generateButton").performClick()
        compose.onNodeWithText("Generating episode").assertIsDisplayed()
        assertEquals(0, servers.openAi.requestCount)
        assertEquals(0, servers.eleven.requestCount)
    }

    @Test fun aPaidRunAsksFirstAndDecliningSendsNothing() {
        live()
        show()
        fillLiveForm()
        compose.onNodeWithTag("generateButton").performClick()

        compose.onNodeWithText("Start paid generation?").assertIsDisplayed()
        compose.onNodeWithText("(an estimate;", substring = true).assertIsDisplayed() // estimates are labelled as estimates
        assertEquals(0, servers.openAi.requestCount) // nothing sent while the question is open

        compose.onNodeWithTag("declineStart").performClick()
        compose.waitForIdle()
        assertNull(container.coordinator.job.value)
        assertEquals(0, servers.openAi.requestCount)
        assertEquals(0, servers.ttsTexts.size)
        compose.onNodeWithText("Create Podcast").assertIsDisplayed()
    }

    @Test fun approvingStartsTheRunAndAnOpenAiRejectionIsShownWithoutRetryAudio() {
        live()
        servers.openAiStatus = 401
        show()
        fillLiveForm()
        compose.onNodeWithTag("generateButton").performClick()
        compose.onNodeWithTag("approveStart").performClick()

        awaitUi { present("errorText") }
        compose.onNodeWithTag("errorText").assertTextContains("rejected the API key", substring = true)
        assertEquals(1, servers.openAi.requestCount) // one request, no automatic repeat
        assertTrue("no script exists, so there is nothing to retry", !present("retryAudioButton"))
    }

    // ---------------------------------------------------------------- retry audio

    @Test fun afterASpeechFailureRetryAudioAsksThenReusesWhatIsDone() {
        live()
        servers.failTtsRequestNumber.set(4)
        show()
        fillLiveForm()
        compose.onNodeWithTag("generateButton").performClick()
        compose.onNodeWithTag("approveStart").performClick()

        awaitUi { present("retryAudioButton") }
        compose.onNodeWithTag("errorText").assertTextContains("server problem", substring = true)
        assertEquals(2, servers.openAi.requestCount)
        assertEquals(4, servers.ttsTexts.size) // 3 accepted + 1 failed

        // asking does not send anything
        servers.failTtsRequestNumber.set(-1)
        compose.onNodeWithTag("retryAudioButton").performClick()
        awaitUi { presentText("Retry audio?") }
        compose.onNodeWithTag("retryDialog").assertIsDisplayed()
        compose.onNodeWithText("3 of 14 segments are already done", substring = true).assertIsDisplayed()
        compose.onNodeWithText("11 will be requested", substring = true).assertIsDisplayed()
        compose.onNodeWithText("not repeated", substring = true).assertIsDisplayed()
        assertEquals(4, servers.ttsTexts.size)

        compose.onNodeWithText("Cancel").performClick()
        compose.waitForIdle()
        assertEquals(4, servers.ttsTexts.size) // declining sent nothing

        compose.onNodeWithTag("retryAudioButton").performClick()
        awaitUi { present("confirmRetry") }
        compose.onNodeWithTag("confirmRetry").performClick()

        awaitUi { present("playEpisodeButton") }
        assertEquals(2, servers.openAi.requestCount) // OpenAI never repeated
        assertEquals(4 + 11, servers.ttsTexts.size) // only the 11 missing segments
        assertEquals(GenerationStatus.COMPLETED, container.coordinator.job.value!!.status)
    }

    @Test fun anInterruptedEpisodeShowsABannerAfterARestartAndCanBeResumedOrDiscarded() {
        live()
        servers.failTtsRequestNumber.set(5)
        container.coordinator.start(enRequest.copy(hostVoice = voice("voiceHost", "Rachel"), guestVoice = voice("voiceGuest", "Adam")))
        awaitCoordinator(container, GenerationStatus.FAILED)
        val id = container.coordinator.job.value!!.id

        // a fresh process: new container, nothing in memory
        val restarted = newContainer()
        show(restarted)
        awaitUi { present("draftBanner") }
        compose.onNodeWithText("Unfinished episode").assertIsDisplayed()
        compose.onNodeWithText("The Waggle Dance", substring = true).assertIsDisplayed()

        servers.failTtsRequestNumber.set(-1)
        compose.onNodeWithTag("retryDraft").performClick()
        awaitUi { present("confirmRetry") }
        compose.onNodeWithText("4 of 14 segments are already done", substring = true).assertIsDisplayed()
        compose.onNodeWithTag("confirmRetry").performClick()

        awaitUi { present("playEpisodeButton") }
        assertEquals(2, servers.openAi.requestCount)
        assertEquals(5 + 10, servers.ttsTexts.size)
        assertEquals(id, restarted.coordinator.job.value!!.episodeId)
    }

    @Test fun discardingFromTheBannerRemovesTheDraft() {
        live()
        servers.failTtsRequestNumber.set(3)
        container.coordinator.start(enRequest.copy(hostVoice = voice("voiceHost", "Rachel"), guestVoice = voice("voiceGuest", "Adam")))
        awaitCoordinator(container, GenerationStatus.FAILED)
        val id = container.coordinator.job.value!!.id
        container.coordinator.reset()

        show(newContainer())
        awaitUi { present("draftBanner") }
        compose.onNodeWithTag("discardDraft").performClick()
        awaitUi { !present("draftBanner") }
        assertNull(runBlocking { container.content.getScript(id) })
        assertTrue(File(File(tmp.root, "drafts"), id).listFiles().orEmpty().isEmpty())
    }

    // ---------------------------------------------------------------- usage and labels

    @Test fun theScriptPageShowsActualUsageNotInventedCosts() {
        runBlocking {
            container.content.saveScript(
                AppContainer.SAMPLE_EPISODE_ID,
                com.podcaststudio.app.domain.PodcastScript(
                    title = "The Waggle Dance", summary = "s", language = Language.ENGLISH,
                    segments = listOf(com.podcaststudio.app.domain.ScriptSegment("s01", com.podcaststudio.app.domain.Speaker.HOST, "Hi")),
                    sources = emptyList(), researchedAt = 1, generatedAt = 2, researchModel = "rm", scriptModel = "sm",
                    targetWords = 750, estimatedDurationSeconds = 300, inputTokens = 4000, outputTokens = 9000,
                ),
            )
            container.content.saveResearch(AppContainer.SAMPLE_EPISODE_ID, sampleResearch().copy(inputTokens = 1200, outputTokens = 3400))
            container.content.addTtsCharacters(AppContainer.SAMPLE_EPISODE_ID, 4875)
        }
        container.seedSampleEpisode()
        show()
        compose.onNodeWithText("Library").performClick()
        awaitUi { present("episode-${AppContainer.SAMPLE_EPISODE_ID}") }
        compose.onNodeWithTag("episode-${AppContainer.SAMPLE_EPISODE_ID}").performClick()
        compose.onNodeWithTag("viewScript").performClick()
        awaitUi { present("usage") }

        compose.onNodeWithText("Research: 1200 tokens in, 3400 out").assertIsDisplayed()
        compose.onNodeWithText("Script: 4000 tokens in, 9000 out").assertIsDisplayed()
        compose.onNodeWithText("4875 characters sent for speech", substring = true).assertIsDisplayed()
        assertTrue("no money amounts", !presentText("$") && !presentText("USD") && !presentText("EUR"))
    }

    @Test fun placeholderAudioIsLabelledAndRealAudioIsNot() {
        runBlocking {
            container.episodes.save(PodcastEpisode("fake", "Simulated speech", "t", 2, Language.ENGLISH, 300, container.sampleAudioUri))
            container.episodes.save(PodcastEpisode("real", "Real speech", "t", 1, Language.ENGLISH, 300, "file:///episodes/real.wav"))
        }
        show()
        compose.onNodeWithText("Library").performClick()
        awaitUi { present("episode-fake") && present("episode-real") }
        assertTrue(present("placeholder-fake"))
        assertTrue(!present("placeholder-real"))
    }

    // ---------------------------------------------------------------- helpers

    private fun voice(id: String, name: String) =
        com.podcaststudio.app.domain.VoiceProfile(id, name, "", setOf(Language.DANISH, Language.ENGLISH))

    /** Waits for a coordinator state without touching Compose, which is far cheaper when no UI is on screen. */
    private fun awaitCoordinator(c: AppContainer, status: GenerationStatus, timeoutMs: Long = 60_000) {
        val end = System.currentTimeMillis() + timeoutMs
        while (c.coordinator.job.value?.status != status) {
            if (System.currentTimeMillis() > end) {
                val j = c.coordinator.job.value
                error("wanted $status but job is ${j?.status} (${j?.stageLabel}) error=${j?.error}; openAi=${servers.openAi.requestCount} tts=${servers.ttsTexts.size}")
            }
            shadowOf(Looper.getMainLooper()).idle()
            Thread.sleep(5)
        }
    }
}
