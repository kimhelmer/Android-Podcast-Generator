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
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.click
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.performTextClearance
import androidx.compose.ui.test.performTextInput
import androidx.media3.exoplayer.ExoPlayer
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.podcaststudio.app.data.local.AppDatabase
import com.podcaststudio.app.domain.AiSettings
import com.podcaststudio.app.domain.InMemoryAiSettingsStore
import com.podcaststudio.app.domain.Language
import com.podcaststudio.app.domain.PodcastEpisode
import com.podcaststudio.app.security.InMemorySecretStore
import com.podcaststudio.app.security.SecretKeyName
import com.podcaststudio.app.ui.PodcastStudioRoot
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.io.File

/** Screens that depend on ElevenLabs voices, speech settings and episode deletion. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w411dp-h891dp-xxhdpi")
class LiveUiTest {
    @get:Rule val compose = createComposeRule()
    @get:Rule val tmp = TemporaryFolder()

    private lateinit var eleven: MockWebServer
    private lateinit var db: AppDatabase
    private lateinit var container: AppContainer
    private val secrets = InMemorySecretStore()
    private val settings = InMemoryAiSettingsStore()

    @Before fun setUp() {
        eleven = MockWebServer().apply {
            enqueue(
                MockResponse().setBody(
                    voicesPageJson(Triple("v1", "Rachel", "https://cdn.example.org/r.mp3"), Triple("v2", "Adam", null)),
                ),
            )
            start()
        }
        val ctx = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(ctx, AppDatabase::class.java).allowMainThreadQueries().build()
        container = AppContainer(
            ctx, db, secrets, settings,
            stepDelayMs = 20,
            elevenLabsBaseUrl = eleven.url("/").toString(),
            requireHttps = false,
            episodeAudioDir = File(tmp.root, "episodes"),
            draftsDir = File(tmp.root, "drafts"),
            playerConnector = { ExoPlayer.Builder(ctx).build() },
        )
    }

    @After fun tearDown() {
        eleven.shutdown()
        db.close()
    }

    private fun show() = compose.setContent { PodcastStudioRoot(container) }

    /** Robolectric only runs work posted to the main thread from other threads when the looper is pumped. */
    private fun awaitUi(timeoutMs: Long = 10_000, condition: () -> Boolean) {
        val end = System.currentTimeMillis() + timeoutMs
        while (!condition()) {
            check(System.currentTimeMillis() < end) { "condition not met within $timeoutMs ms" }
            shadowOf(Looper.getMainLooper()).idle()
            compose.waitForIdle()
            Thread.sleep(20)
        }
    }

    private fun count(tag: String) = compose.onAllNodes(hasTestTag(tag)).fetchSemanticsNodes().size
    private fun countText(t: String) = compose.onAllNodes(hasText(t, substring = true)).fetchSemanticsNodes().size

    private fun enableLiveSpeech(withKey: Boolean = true) {
        if (withKey) secrets.put(SecretKeyName.ELEVENLABS, FAKE_EL_KEY)
        settings.set(AiSettings(useLiveElevenLabs = true))
    }

    // ---- voices on the Create screen ----

    @Test fun liveVoiceNamesAppearInBothSelectorsWithPreviewWhereAvailable() {
        enableLiveSpeech()
        show()
        awaitUi { eleven.requestCount >= 1 }
        awaitUi { countText("Loading voices") == 0 }

        compose.onNodeWithTag("hostVoice").performClick()
        compose.onNodeWithText("Rachel - british, female, narration").assertIsDisplayed()
        compose.onNodeWithText("Adam - british, female, narration").assertIsDisplayed()
        assertEquals("only the voice with a sample gets a preview button", 1, count("preview-v1"))
        assertEquals(0, count("preview-v2"))
        compose.onNodeWithText("Adam - british, female, narration").performClick()

        compose.onNodeWithTag("guestVoice").performClick()
        // tap the left of the row: the centre of this row is where its preview button sits
        compose.onNodeWithText("Rachel - british, female, narration").performTouchInput { click(Offset(24f, centerY)) }
        compose.onNodeWithTag("hostVoice").assertTextContains("Adam", substring = true)
        compose.onNodeWithTag("guestVoice").assertTextContains("Rachel", substring = true)
    }

    @Test fun missingKeyShowsAReadableErrorInsteadOfVoices() {
        enableLiveSpeech(withKey = false)
        show()
        awaitUi { count("voiceError") == 1 }
        compose.onNodeWithTag("voiceError").assertTextContains("API key", substring = true)
        assertEquals(0, eleven.requestCount)
    }

    @Test fun createScreenStatesTheCostOfLiveSpeech() {
        enableLiveSpeech()
        show()
        compose.onNodeWithTag("modeNote").assertTextContains("ElevenLabs characters", substring = true)
        compose.onNodeWithTag("modeNote").assertTextContains("4875", substring = true) // 5 min x 150 wpm x 6.5
    }

    // ---- speech settings ----

    @Test fun speechSettingsValidateAndPersist() {
        show()
        compose.onNodeWithText("Settings").performClick()
        compose.onNodeWithTag("liveTtsSwitch").performScrollTo().performClick()
        assertEquals(true, settings.get().useLiveElevenLabs)
        compose.onNodeWithTag("liveTtsNeedsKey").performScrollTo().assertIsDisplayed()

        compose.onNodeWithTag("maxChars").performScrollTo().performTextClearance()
        compose.onNodeWithTag("maxChars").performTextInput("50")
        compose.onNodeWithTag("saveTts").performScrollTo().performClick()
        compose.onNodeWithTag("ttsMessage").assertTextContains("1000 to 100000", substring = true)
        assertEquals(AiSettings.DEFAULT_MAX_TTS_CHARACTERS, settings.get().maxTtsCharacters)

        compose.onNodeWithTag("maxChars").performTextClearance()
        compose.onNodeWithTag("maxChars").performTextInput("12000")
        compose.onNodeWithTag("ttsModel").performScrollTo().performTextClearance()
        compose.onNodeWithTag("ttsModel").performTextInput("eleven_v3")
        compose.onNodeWithTag("saveTts").performScrollTo().performClick()
        assertEquals(12_000, settings.get().maxTtsCharacters)
        assertEquals("eleven_v3", settings.get().ttsModel)
    }

    @Test fun modelInfoExplainsCapabilities() {
        show()
        compose.onNodeWithText("Settings").performClick()
        compose.onNodeWithTag("ttsModelInfo", useUnmergedTree = true).performScrollTo().assertTextContains("Delivery directions are not used", substring = true)
        compose.onNodeWithTag("ttsModel").performScrollTo().performTextClearance()
        compose.onNodeWithTag("ttsModel").performTextInput("eleven_v3")
        compose.onNodeWithTag("ttsModelInfo", useUnmergedTree = true).assertTextContains("and delivery directions", substring = true)
        compose.onNodeWithTag("ttsModel").performTextClearance()
        compose.onNodeWithTag("ttsModel").performTextInput("something_new")
        compose.onNodeWithTag("ttsModelInfo", useUnmergedTree = true).assertTextContains("Unrecognised", substring = true)
    }

    // ---- library delete ----

    private fun addGeneratedEpisode(): File {
        val wav = File(tmp.root, "episodes").also { it.mkdirs() }.resolve("e1.wav").also { it.writeBytes(ByteArray(100)) }
        runBlocking {
            container.episodes.save(
                PodcastEpisode("e1", "Bees episode", "t", System.currentTimeMillis(), Language.ENGLISH, 60, "file:///${wav.absolutePath.replace('\\', '/')}"),
            )
        }
        return wav
    }

    @Test fun deletingAnEpisodeAsksFirstThenRemovesItAndItsAudio() {
        val wav = addGeneratedEpisode()
        show()
        compose.onNodeWithText("Library").performClick()
        compose.waitUntil(10_000) { count("delete-e1") == 1 }

        compose.onNodeWithTag("delete-e1").performClick()
        compose.onNodeWithText("Delete episode?").assertIsDisplayed()
        compose.onNodeWithText("Cancel").performClick()
        assertNotNull(runBlocking { container.episodes.getEpisode("e1") }) // cancelling changes nothing

        compose.onNodeWithTag("delete-e1").performClick()
        compose.onNodeWithTag("confirmDelete").performClick()
        // row, stored script and audio file are removed one after another, so wait for the last step
        awaitUi { runBlocking { container.episodes.getEpisode("e1") } == null && !wav.exists() }
        assertNull(runBlocking { container.content.getScript("e1") })
    }

    @Test fun theSampleEpisodeHasNoDeleteButton() {
        container.seedSampleEpisode()
        show()
        compose.onNodeWithText("Library").performClick()
        compose.waitUntil(10_000) { count("episode-${AppContainer.SAMPLE_EPISODE_ID}") == 1 }
        assertEquals(0, count("delete-${AppContainer.SAMPLE_EPISODE_ID}"))
    }
}
