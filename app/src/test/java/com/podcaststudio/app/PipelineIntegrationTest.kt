package com.podcaststudio.app

import android.content.Context
import android.os.Looper
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.podcaststudio.app.data.local.AppDatabase
import com.podcaststudio.app.domain.AiSettings
import com.podcaststudio.app.domain.GenerationStatus
import com.podcaststudio.app.domain.InMemoryAiSettingsStore
import com.podcaststudio.app.security.InMemorySecretStore
import com.podcaststudio.app.security.SecretKeyName
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.time.Duration

/** Whole pipeline through AppContainer, with a local MockWebServer standing in for OpenAI. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class PipelineIntegrationTest {
    private lateinit var server: MockWebServer
    private lateinit var db: AppDatabase
    private lateinit var container: AppContainer
    private val secrets = InMemorySecretStore()
    private val settings = InMemoryAiSettingsStore()
    private val seen = mutableListOf<RecordedRequest>()

    @Before fun setUp() {
        server = MockWebServer()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                seen += request
                val body = Json.parseToJsonElement(request.body.readUtf8()).jsonObject
                return if (body.containsKey("tools")) {
                    MockResponse().setBody(
                        responsesJson("Bees dance. [verified]", listOf("Bee study" to "https://example.org/bees?utm_source=openai")),
                    )
                } else {
                    MockResponse().setBody(responsesJson(scriptJson()))
                }
            }
        }
        server.start()
        val ctx = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(ctx, AppDatabase::class.java).allowMainThreadQueries().build()
        container = AppContainer(
            ctx, db, secrets, settings,
            stepDelayMs = 10,
            openAiBaseUrl = server.url("/").toString(),
            requireHttps = false,
        )
    }

    @After fun tearDown() {
        server.shutdown()
        db.close()
    }

    private fun awaitTerminal(): GenerationStatus {
        repeat(200) {
            // advance virtual time so delay() in the mock stages elapses, and run any resumed coroutines
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(50))
            container.coordinator.job.value?.status?.takeIf { it.isTerminal }?.let { return it }
            Thread.sleep(25)
        }
        error("generation did not finish; last status=${container.coordinator.job.value?.status}")
    }

    @Test fun liveModeRunsResearchThenScriptAndPersistsBoth() {
        secrets.put(SecretKeyName.OPENAI, FAKE_KEY)
        settings.set(AiSettings(useLiveOpenAi = true, researchModel = "rm", scriptModel = "sm"))

        container.coordinator.start(enRequest)
        assertEquals(GenerationStatus.COMPLETED, awaitTerminal())

        assertEquals(2, seen.size)
        seen.forEach { assertEquals("Bearer $FAKE_KEY", it.getHeader("Authorization")) }

        val id = container.coordinator.job.value!!.episodeId!!
        val research = runBlocking { container.content.getResearch(id) }
        val script = runBlocking { container.content.getScript(id) }
        assertNotNull(research)
        assertNotNull(script)
        assertEquals("Bees dance. [verified]", research!!.notes)
        assertEquals("https://example.org/bees", research.sources.single().url)
        assertEquals("The Waggle Dance", script!!.title)
        assertEquals(research.sources, script.sources)

        val episode = runBlocking { container.episodes.getEpisode(id) }
        assertEquals("The Waggle Dance", episode!!.title)
        assertEquals(container.sampleAudioUri, episode.audioUri) // audio is still the M1A placeholder
    }

    @Test fun defaultSettingsMakeNoNetworkCallsAndStillWork() {
        secrets.put(SecretKeyName.OPENAI, FAKE_KEY) // a stored key alone must not trigger calls
        assertFalse(settings.get().useLiveOpenAi)

        container.coordinator.start(enRequest)
        assertEquals(GenerationStatus.COMPLETED, awaitTerminal())

        assertEquals(0, server.requestCount)
        val id = container.coordinator.job.value!!.episodeId!!
        assertEquals("mock", runBlocking { container.content.getScript(id) }!!.scriptModel)
    }

    @Test fun liveModeWithoutKeyFailsClearlyAndSendsNothing() {
        settings.set(AiSettings(useLiveOpenAi = true))

        container.coordinator.start(enRequest)
        assertEquals(GenerationStatus.FAILED, awaitTerminal())

        assertEquals(0, server.requestCount)
        val error = container.coordinator.job.value!!.error!!
        assertTrue(error.contains("API key"))
        assertNull(runBlocking { container.episodes.getEpisode(container.coordinator.job.value!!.id) })
    }

    @Test fun apiFailureSurfacesSafeMessageAndKeepsNothingBeyondResearch() {
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                seen += request
                return MockResponse().setResponseCode(401).setBody("""{"error":{"message":"bad key $FAKE_KEY"}}""")
            }
        }
        secrets.put(SecretKeyName.OPENAI, FAKE_KEY)
        settings.set(AiSettings(useLiveOpenAi = true))

        container.coordinator.start(enRequest)
        assertEquals(GenerationStatus.FAILED, awaitTerminal())

        val error = container.coordinator.job.value!!.error!!
        assertTrue(error.contains("rejected the API key"))
        assertFalse(error.contains(FAKE_KEY))
        assertEquals(1, server.requestCount)
    }
}
