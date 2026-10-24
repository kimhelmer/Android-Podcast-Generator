package com.podcaststudio.app

import android.content.Context
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextClearance
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.ComposeContentTestRule
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.media3.exoplayer.ExoPlayer
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.podcaststudio.app.data.local.AppDatabase
import com.podcaststudio.app.domain.InMemoryAiSettingsStore
import com.podcaststudio.app.domain.Language
import com.podcaststudio.app.domain.PodcastScript
import com.podcaststudio.app.domain.ScriptSegment
import com.podcaststudio.app.domain.SourceRef
import com.podcaststudio.app.domain.Speaker
import com.podcaststudio.app.security.InMemorySecretStore
import com.podcaststudio.app.security.SecretKeyName
import com.podcaststudio.app.ui.PodcastStudioRoot
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w411dp-h891dp-xxhdpi")
class NavigationTest {
    @get:Rule val compose = createComposeRule()

    private lateinit var db: AppDatabase
    private lateinit var secrets: InMemorySecretStore
    private lateinit var aiSettings: InMemoryAiSettingsStore
    private lateinit var container: AppContainer

    @Before fun setUp() {
        val ctx = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(ctx, AppDatabase::class.java).allowMainThreadQueries().build()
        secrets = InMemorySecretStore()
        aiSettings = InMemoryAiSettingsStore()
        container = AppContainer(
            ctx, db, secrets, aiSettings,
            stepDelayMs = 50,
            // a plain player stands in for the background service, which Robolectric cannot bind
            playerConnector = { ExoPlayer.Builder(ctx).build() },
        )
        container.seedSampleEpisode()
        compose.setContent { PodcastStudioRoot(container) }
    }

    @After fun tearDown() = db.close()

    @Test fun startsOnCreateScreen() {
        compose.onNodeWithText("Create Podcast").assertIsDisplayed()
        compose.onNodeWithTag("generateButton").assertIsDisplayed()
    }

    @Test fun bottomBarReachesLibraryAndSettingsAndBack() {
        compose.onNodeWithText("Library").performClick()
        compose.onNodeWithText("Podcast Library").assertIsDisplayed()
        compose.onNodeWithText("Settings").performClick()
        compose.onNodeWithText("OpenAI API key").assertIsDisplayed()
        compose.onNodeWithText("ElevenLabs API key").assertIsDisplayed()
        compose.onNodeWithText("Create").performClick()
        compose.onNodeWithText("Create Podcast").assertIsDisplayed()
    }

    @Test fun libraryShowsBundledSampleEpisode() {
        compose.onNodeWithText("Library").performClick()
        compose.waitUntil(5_000) {
            compose.onAllNodesWithTagCount("episode-${AppContainer.SAMPLE_EPISODE_ID}") > 0
        }
        compose.onNodeWithText("Sample episode (bundled tones)").assertIsDisplayed()
        compose.onNodeWithText("0:24", substring = true).assertIsDisplayed()
    }

    @Test fun tappingSampleEpisodeOpensPlayer() {
        compose.onNodeWithText("Library").performClick()
        compose.waitUntil(5_000) {
            compose.onAllNodesWithTagCount("episode-${AppContainer.SAMPLE_EPISODE_ID}") > 0
        }
        compose.onNodeWithTag("episode-${AppContainer.SAMPLE_EPISODE_ID}").performClick()
        compose.onNodeWithTag("playPause").assertIsDisplayed()
        compose.onNodeWithTag("seekBar").assertIsDisplayed()
    }

    @Test fun generateWithEmptyFormShowsValidationAndStaysPut() {
        compose.onNodeWithTag("generateButton").performClick()
        compose.onNodeWithText("Describe the topic of the podcast.").assertIsDisplayed()
        compose.onNodeWithText("Choose a host voice.").assertIsDisplayed()
        compose.onNodeWithText("Create Podcast").assertIsDisplayed()
    }

    @Test fun validFormStartsSimulatedGeneration() {
        compose.onNodeWithTag("topicField").performTextInput("How honeybees communicate")
        compose.waitForIdle()
        compose.onNodeWithTag("hostVoice").performClick()
        compose.onNodeWithText("Anna - Warm, conversational").performClick()
        compose.onNodeWithTag("guestVoice").performClick()
        compose.onNodeWithText("Mads - Calm, authoritative").performClick()
        compose.onNodeWithTag("generateButton").performClick()
        compose.onNodeWithText("Generating episode").assertIsDisplayed()
        compose.onNodeWithTag("stageLabel").assertIsDisplayed()
    }

    @Test fun savedKeyIsNeverShownInPlaintext() {
        compose.onNodeWithText("Settings").performClick()
        compose.onNodeWithTag("openai-field").performTextInput("s" + "k-test-not-a-real-key")
        compose.onNodeWithTag("openai-save").performClick()
        compose.onNodeWithText("A key is stored (hidden)").assertIsDisplayed()
        assertEquals("s" + "k-test-not-a-real-key", secrets.get(SecretKeyName.OPENAI))
        // input cleared after save, and the value appears nowhere in the UI tree
        compose.onAllNodesWithText("s" + "k-test-not-a-real-key", substring = true).assertCountEquals(0)
        compose.onAllNodesWithText("sk-test", substring = true).assertCountEquals(0)
    }

    @Test fun simulatedModeIsAnnouncedOnCreateScreen() {
        compose.onNodeWithTag("modeNote").assertTextContains("nothing is sent", substring = true, ignoreCase = true)
    }

    @Test fun liveSwitchPersistsAndWarnsWhenNoKey() {
        compose.onNodeWithText("Settings").performClick()
        compose.onNodeWithTag("liveSwitch").performScrollTo().performClick()
        assertEquals(true, aiSettings.get().useLiveOpenAi)
        compose.onNodeWithTag("liveNeedsKey").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Create").performClick()
        compose.onNodeWithTag("modeNote").assertTextContains("sent to OpenAI", substring = true)
    }

    @Test fun invalidSpeakingRateIsRejectedAndValidOneSaved() {
        compose.onNodeWithText("Settings").performClick()
        compose.onNodeWithTag("wpm").performScrollTo().performTextClearance()
        compose.onNodeWithTag("wpm").performTextInput("30")
        compose.onNodeWithTag("saveAi").performScrollTo().performClick()
        compose.onNodeWithTag("aiMessage").assertTextContains("100 to 220", substring = true)
        assertEquals(150, aiSettings.get().wordsPerMinute)

        compose.onNodeWithTag("wpm").performTextClearance()
        compose.onNodeWithTag("wpm").performTextInput("170")
        compose.onNodeWithTag("researchModel").performScrollTo().performTextClearance()
        compose.onNodeWithTag("researchModel").performTextInput("my-search-model")
        compose.onNodeWithTag("saveAi").performScrollTo().performClick()
        assertEquals(170, aiSettings.get().wordsPerMinute)
        assertEquals("my-search-model", aiSettings.get().researchModel)
    }

    @Test fun playerLinksToStoredScriptWithSources() {
        kotlinx.coroutines.runBlocking {
            container.content.saveScript(
                AppContainer.SAMPLE_EPISODE_ID,
                PodcastScript(
                    title = "The Waggle Dance",
                    summary = "How bees talk.",
                    language = Language.ENGLISH,
                    segments = listOf(
                        ScriptSegment("s01", Speaker.HOST, "Welcome to the hive.", "warm", "Introduction"),
                        ScriptSegment("s02", Speaker.GUEST, "Glad to be here.", null, "Introduction"),
                    ),
                    sources = listOf(SourceRef("Bee study", "https://example.org/bees")),
                    researchedAt = 1_700_000_000_000,
                    generatedAt = 1_700_000_100_000,
                    researchModel = "rm",
                    scriptModel = "sm",
                    targetWords = 750,
                    estimatedDurationSeconds = 300,
                ),
            )
        }
        compose.onNodeWithText("Library").performClick()
        compose.waitUntil(5_000) { compose.onAllNodesWithTagCount("episode-${AppContainer.SAMPLE_EPISODE_ID}") > 0 }
        compose.onNodeWithTag("episode-${AppContainer.SAMPLE_EPISODE_ID}").performClick()
        compose.onNodeWithTag("viewScript").performClick()
        compose.waitUntil(5_000) { compose.onAllNodesWithTagCount("scriptTitle") > 0 }
        compose.onNodeWithText("The Waggle Dance").assertIsDisplayed()
        compose.onNodeWithText("HOST (warm)").assertIsDisplayed()
        compose.onNodeWithText("Welcome to the hive.").assertIsDisplayed()
        compose.onNodeWithText("Bee study").assertIsDisplayed()
        compose.onNodeWithTag("scriptMeta").assertTextContains("rm", substring = true)
    }

    @Test fun episodeWithoutScriptSaysSo() {
        compose.onNodeWithText("Library").performClick()
        compose.waitUntil(5_000) { compose.onAllNodesWithTagCount("episode-${AppContainer.SAMPLE_EPISODE_ID}") > 0 }
        compose.onNodeWithTag("episode-${AppContainer.SAMPLE_EPISODE_ID}").performClick()
        compose.onNodeWithTag("viewScript").performClick()
        compose.waitUntil(5_000) { compose.onAllNodesWithTagCount("noScript") > 0 }
    }

    private fun ComposeContentTestRule.onAllNodesWithTagCount(tag: String): Int =
        onAllNodes(hasTestTag(tag)).fetchSemanticsNodes().size
}
