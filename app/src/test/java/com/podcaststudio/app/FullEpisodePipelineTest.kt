package com.podcaststudio.app

import android.content.Context
import android.os.Looper
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.podcaststudio.app.data.local.AppDatabase
import com.podcaststudio.app.domain.AiSettings
import com.podcaststudio.app.domain.GenerationStatus
import com.podcaststudio.app.domain.InMemoryAiSettingsStore
import com.podcaststudio.app.domain.Language
import com.podcaststudio.app.domain.PodcastRequest
import com.podcaststudio.app.domain.VoiceProfile
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
import okio.Buffer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
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
import java.net.URI
import java.time.Duration

/** A complete episode through AppContainer, with OpenAI and ElevenLabs both replaced by local servers. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class FullEpisodePipelineTest {
    @get:Rule val tmp = TemporaryFolder()

    private lateinit var openAi: MockWebServer
    private lateinit var eleven: MockWebServer
    private lateinit var db: AppDatabase
    private lateinit var container: AppContainer
    private val secrets = InMemorySecretStore()
    private val settings = InMemoryAiSettingsStore()

    private class TtsCall(val voiceId: String, val text: String, val modelId: String, val key: String?, val format: String?)

    private val ttsCalls = java.util.Collections.synchronizedList(mutableListOf<TtsCall>())
    /** 1-based number of the TTS request that should fail with HTTP 503; -1 for none. Failed requests are still counted in ttsCalls. */
    private val failTtsRequestNumber = java.util.concurrent.atomic.AtomicInteger(-1)
    private val elevenPaths = java.util.Collections.synchronizedList(mutableListOf<String>())
    private val openAiBodies = java.util.Collections.synchronizedList(mutableListOf<String>())

    private val hostVoice = VoiceProfile("voiceHost", "Rachel", "", setOf(Language.DANISH, Language.ENGLISH))
    private val guestVoice = VoiceProfile("voiceGuest", "Adam", "", setOf(Language.DANISH, Language.ENGLISH))
    private val request = PodcastRequest("How honeybees communicate", Language.ENGLISH, 5, hostVoice, guestVoice)

    @Before fun setUp() {
        openAi = MockWebServer().apply {
            dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse {
                    val body = request.body.readUtf8()
                    openAiBodies += body
                    val hasTools = Json.parseToJsonElement(body).jsonObject.containsKey("tools")
                    return MockResponse().setBody(
                        if (hasTools) responsesJson("Bees dance. [verified]", listOf("Bee study" to "https://example.org/bees"))
                        else responsesJson(scriptJson()),
                    )
                }
            }
            start()
        }
        eleven = MockWebServer().apply {
            dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse {
                    elevenPaths += request.path!!
                    if (request.path!!.startsWith("/v2/voices")) {
                        return MockResponse().setBody(voicesPageJson(Triple("voiceHost", "Rachel", null), Triple("voiceGuest", "Adam", null)))
                    }
                    val json = Json.parseToJsonElement(request.body.readUtf8()).jsonObject
                    val text = json["text"]!!.jsonPrimitive.content
                    ttsCalls += TtsCall(
                        voiceId = request.path!!.substringAfter("/v1/text-to-speech/").substringBefore("?"),
                        text = text,
                        modelId = json["model_id"]!!.jsonPrimitive.content,
                        key = request.getHeader("xi-api-key"),
                        format = request.requestUrl?.queryParameter("output_format"),
                    )
                    if (ttsCalls.size == failTtsRequestNumber.get()) return MockResponse().setResponseCode(503)
                    val words = text.trim().split(Regex("\\s+")).size
                    val pcm = pcmTone(toneMs = words * 130 + 400, headMs = 100, tailMs = 100)
                    return MockResponse().setHeader("Content-Type", "audio/pcm").setBody(Buffer().write(pcm))
                }
            }
            start()
        }
        val ctx = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(ctx, AppDatabase::class.java).allowMainThreadQueries().build()
        container = AppContainer(
            ctx, db, secrets, settings,
            stepDelayMs = 10,
            openAiBaseUrl = openAi.url("/").toString(),
            requireHttps = false,
            elevenLabsBaseUrl = eleven.url("/").toString(),
            episodeAudioDir = File(tmp.root, "episodes"),
            draftsDir = File(tmp.root, "drafts"),
            pauseBetweenTurnsMs = 150,
        )
    }

    @After fun tearDown() {
        openAi.shutdown()
        eleven.shutdown()
        db.close()
    }

    private fun goLive(openAiOn: Boolean = true, elevenOn: Boolean = true, maxChars: Int = 20_000) {
        secrets.put(SecretKeyName.OPENAI, FAKE_KEY)
        secrets.put(SecretKeyName.ELEVENLABS, FAKE_EL_KEY)
        settings.set(AiSettings(useLiveOpenAi = openAiOn, useLiveElevenLabs = elevenOn, maxTtsCharacters = maxChars))
    }

    private fun awaitTerminal(): GenerationStatus {
        repeat(400) {
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(50))
            container.coordinator.job.value?.status?.takeIf { it.isTerminal }?.let { return it }
            Thread.sleep(20)
        }
        error("did not finish; last=${container.coordinator.job.value?.status} ${container.coordinator.job.value?.stageLabel}")
    }

    @Test fun completeEpisodeIsSpokenInOrderWithTheRightVoicesAndSavedAsPlayableAudio() {
        goLive()
        container.coordinator.start(request)
        assertEquals(GenerationStatus.COMPLETED, awaitTerminal())

        val id = container.coordinator.job.value!!.episodeId!!
        val script = runBlocking { container.content.getScript(id) }!!

        // one TTS request per segment, in script order, alternating the two chosen voices
        assertEquals(script.segments.size, ttsCalls.size)
        assertEquals(script.segments.map { it.text }, ttsCalls.map { it.text }) // exact spoken text, in order
        assertEquals(
            script.segments.indices.map { if (it % 2 == 0) "voiceHost" else "voiceGuest" },
            ttsCalls.map { it.voiceId },
        )
        assertTrue(ttsCalls.all { it.key == FAKE_EL_KEY && it.format == "pcm_24000" })
        assertTrue(ttsCalls.all { it.modelId == "eleven_multilingual_v2" })

        // nothing but dialogue is spoken: no delivery words, section titles, URLs or source titles
        val spoken = ttsCalls.joinToString(" ") { it.text }
        assertFalse(spoken.contains("thoughtful"))
        assertFalse(spoken.contains("Introduction"))
        assertFalse(spoken.contains("http"))
        assertFalse(spoken.contains("Bee study"))

        // the stored episode points at a real, parseable WAV whose length matches the library duration
        val episode = runBlocking { container.episodes.getEpisode(id) }!!
        assertTrue(episode.audioUri.startsWith("file:///"))
        val wav = File(URI(episode.audioUri))
        assertTrue(wav.isFile)
        val seconds = parseWav(wav).seconds // strict: size fields must match the real file
        assertEquals(seconds.toDouble(), episode.durationSeconds.toDouble(), 1.0)
        // speech is trimmed and joined with 150 ms pauses, so it is the sum of the clips, not their raw total
        val expectedMs = script.segments.sumOf { it.text.trim().split(Regex("\\s+")).size * 130L + 400 + 80 } + 150L * (script.segments.size - 1)
        assertEquals(expectedMs.toDouble(), seconds * 1000.0, expectedMs * 0.01)

        // temporary clips are gone, the OpenAI script/sources are still there
        assertTrue(File(tmp.root, "drafts").listFiles().orEmpty().isEmpty())
        assertEquals("https://example.org/bees", script.sources.single().url)
    }

    @Test fun deleteRemovesRowContentAndAudioFile() {
        goLive()
        container.coordinator.start(request)
        assertEquals(GenerationStatus.COMPLETED, awaitTerminal())
        val id = container.coordinator.job.value!!.episodeId!!
        val episode = runBlocking { container.episodes.getEpisode(id) }!!
        val wav = File(URI(episode.audioUri))
        assertTrue(wav.isFile)

        runBlocking { container.deleteEpisode(episode) }

        assertNull(runBlocking { container.episodes.getEpisode(id) })
        assertNull(runBlocking { container.content.getScript(id) })
        assertFalse(wav.exists())
    }

    @Test fun theBundledSampleCannotBeDeleted() {
        container.seedSampleEpisode()
        var sample: com.podcaststudio.app.domain.PodcastEpisode? = null
        repeat(100) {
            shadowOf(Looper.getMainLooper()).idle()
            sample = sample ?: runBlocking { container.episodes.getEpisode(AppContainer.SAMPLE_EPISODE_ID) }
            if (sample == null) Thread.sleep(20)
        }
        assertNotNull(sample)
        runBlocking { container.deleteEpisode(sample!!) }
        shadowOf(Looper.getMainLooper()).idle()
        assertNotNull(runBlocking { container.episodes.getEpisode(AppContainer.SAMPLE_EPISODE_ID) })
    }

    @Test fun overBudgetEpisodeIsRefusedBeforeAnyPaidCallToEitherService() {
        goLive(maxChars = 1_000) // 5 min needs ~4,900 characters
        container.coordinator.start(request)
        assertEquals(GenerationStatus.FAILED, awaitTerminal())

        assertTrue(container.coordinator.job.value!!.error!!.contains("limit"))
        assertEquals(0, openAi.requestCount)
        assertEquals(0, eleven.requestCount)
    }

    @Test fun elevenLabsOffKeepsTheSampleAudioAndNeverCallsElevenLabs() {
        goLive(elevenOn = false)
        container.coordinator.start(request)
        assertEquals(GenerationStatus.COMPLETED, awaitTerminal())

        assertEquals(0, eleven.requestCount)
        val id = container.coordinator.job.value!!.episodeId!!
        assertEquals(container.sampleAudioUri, runBlocking { container.episodes.getEpisode(id) }!!.audioUri)
    }

    @Test fun openAiOffStillSpeaksTheSimulatedScriptThroughElevenLabs() {
        goLive(openAiOn = false)
        container.coordinator.start(request)
        assertEquals(GenerationStatus.COMPLETED, awaitTerminal())

        assertEquals(0, openAi.requestCount)
        assertEquals(4, ttsCalls.size)
        assertTrue(ttsCalls[0].text.startsWith("Welcome to the show"))
    }

    @Test fun defaultSettingsNeverTouchEitherServer() {
        secrets.put(SecretKeyName.OPENAI, FAKE_KEY)
        secrets.put(SecretKeyName.ELEVENLABS, FAKE_EL_KEY)
        assertFalse(settings.get().useLiveOpenAi || settings.get().useLiveElevenLabs)

        container.coordinator.start(request)
        assertEquals(GenerationStatus.COMPLETED, awaitTerminal())
        assertEquals(0, openAi.requestCount)
        assertEquals(0, eleven.requestCount)
    }

    @Test fun liveSpeechWithoutAKeyFailsClearlyAndSendsNothing() {
        settings.set(AiSettings(useLiveElevenLabs = true))
        container.coordinator.start(request)
        assertEquals(GenerationStatus.FAILED, awaitTerminal())
        val error = container.coordinator.job.value!!.error!!
        assertTrue(error, error.contains("ElevenLabs") && error.contains("API key"))
        assertEquals(0, eleven.requestCount)
        assertEquals(0, openAi.requestCount)
    }

    @Test fun rejectedElevenLabsKeyFailsWithASafeMessageAndKeepsTheScript() {
        eleven.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest) =
                MockResponse().setResponseCode(401).setBody("""{"detail":{"code":"invalid_api_key","message":"bad $FAKE_EL_KEY"}}""")
        }
        goLive()
        container.coordinator.start(request)
        assertEquals(GenerationStatus.FAILED, awaitTerminal())

        val job = container.coordinator.job.value!!
        assertTrue(job.error!!.contains("rejected the API key"))
        assertFalse(job.error!!.contains(FAKE_EL_KEY))
        assertNotNull(runBlocking { container.content.getScript(job.id) }) // the paid OpenAI script is not lost
        assertTrue(runBlocking { container.episodes.getEpisode(job.id) } == null)
        assertTrue(File(tmp.root, "drafts").listFiles().orEmpty().isEmpty())
    }

    @Test fun voiceListComesFromElevenLabsOnlyWhenLive() {
        val mock = runBlocking { container.voices.availableVoices() }
        assertTrue(mock.any { it.id.startsWith("mock-") })
        assertEquals(0, eleven.requestCount)

        goLive()
        val live = runBlocking { container.voices.availableVoices() }
        assertEquals(listOf("Adam", "Rachel"), live.map { it.name })
        assertTrue(elevenPaths.single().startsWith("/v2/voices"))
    }

    // ---------------------------------------------------------------- resume after a failed audio stage

    private fun draftClipFiles(draftId: String) =
        File(File(tmp.root, "drafts"), draftId).listFiles().orEmpty().filter { it.name.endsWith(".pcm") }

    @Test fun failedSpeechIsResumedWithoutRepeatingOpenAiWorkOrPaidSegments() {
        goLive()
        failTtsRequestNumber.set(6) // the 6th speech request is rejected by the server
        container.coordinator.start(request)
        assertEquals(GenerationStatus.FAILED, awaitTerminal())

        val job = container.coordinator.job.value!!
        val id = job.id
        val script = runBlocking { container.content.getScript(id) }!!
        assertEquals(2, openAi.requestCount) // research + script, once each
        assertEquals(6, ttsCalls.size) // five accepted + the one that failed
        assertEquals(5, draftClipFiles(id).size) // accepted segments are kept on disk
        assertTrue(job.hasScript)
        assertNull(runBlocking { container.episodes.getEpisode(id) })
        assertTrue(job.error!!.contains("server problem"))
        // usage reflects only what was accepted, never the failed request
        assertEquals(script.segments.take(5).sumOf { it.text.length }, runBlocking { container.content.getTtsCharacters(id) })

        val plan = runBlocking { container.coordinator.planRetry(id) }!!
        assertEquals(14, plan.totalSegments)
        assertEquals(5, plan.reusableSegments)
        assertEquals(script.segments.drop(5).sumOf { it.text.length }, plan.charactersToSend)

        failTtsRequestNumber.set(-1)
        assertTrue(runBlocking { container.coordinator.retryAudio(id) })
        assertEquals(GenerationStatus.COMPLETED, awaitTerminal())

        assertEquals("OpenAI must not be called again", 2, openAi.requestCount)
        assertEquals("only the 9 missing segments were requested", 6 + 9, ttsCalls.size)
        val retryCalls = ttsCalls.drop(6)
        assertEquals(script.segments.drop(5).map { it.text }, retryCalls.map { it.text })
        assertEquals(
            script.segments.indices.drop(5).map { if (it % 2 == 0) "voiceHost" else "voiceGuest" },
            retryCalls.map { it.voiceId },
        )
        assertEquals(5, container.coordinator.job.value!!.audioReused)

        // the finished episode is real, complete audio in script order, and the draft is cleaned up
        val episode = runBlocking { container.episodes.getEpisode(id) }!!
        assertTrue(episode.audioUri.startsWith("file:///"))
        val wav = parseWav(File(URI(episode.audioUri)))
        val expectedMs = script.segments.sumOf { it.text.trim().split(Regex("\\s+")).size * 130L + 400 + 80 } + 150L * 13
        assertEquals(expectedMs.toDouble(), wav.seconds * 1000.0, expectedMs * 0.01)
        assertEquals(wav.seconds, episode.durationSeconds.toDouble(), 1.0)
        assertTrue(File(tmp.root, "drafts").listFiles().orEmpty().isEmpty())
        assertEquals(script.segments.sumOf { it.text.length }, runBlocking { container.content.getTtsCharacters(id) })
        assertNull("finished, so no longer a draft", runBlocking { container.coordinator.unfinishedDraft() })
    }

    @Test fun anInterruptedEpisodeCanBeResumedAfterTheAppRestarts() {
        goLive()
        failTtsRequestNumber.set(4)
        container.coordinator.start(request)
        assertEquals(GenerationStatus.FAILED, awaitTerminal())
        val id = container.coordinator.job.value!!.id
        val openAiCalls = openAi.requestCount

        // a brand-new container over the same database and files, like a fresh process
        val ctx = ApplicationProvider.getApplicationContext<Context>()
        val restarted = AppContainer(
            ctx, db, secrets, settings,
            stepDelayMs = 10,
            openAiBaseUrl = openAi.url("/").toString(),
            requireHttps = false,
            elevenLabsBaseUrl = eleven.url("/").toString(),
            episodeAudioDir = File(tmp.root, "episodes"),
            draftsDir = File(tmp.root, "drafts"),
            pauseBetweenTurnsMs = 150,
        )
        assertNull(restarted.coordinator.job.value) // nothing in memory
        val draft = runBlocking { restarted.coordinator.unfinishedDraft() }!!
        assertEquals(id, draft.id)
        assertEquals(request, draft.request) // voices and language were persisted
        assertEquals(14, draft.segmentCount)

        failTtsRequestNumber.set(-1)
        assertTrue(runBlocking { restarted.coordinator.retryAudio(id) })
        repeat(400) {
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(50))
            if (restarted.coordinator.job.value?.status?.isTerminal == true) return@repeat
            Thread.sleep(20)
        }
        assertEquals(GenerationStatus.COMPLETED, restarted.coordinator.job.value!!.status)
        assertEquals(openAiCalls, openAi.requestCount)
        assertEquals(4 + 11, ttsCalls.size) // 3 accepted + 1 failed, then the 11 missing segments
        assertNotNull(runBlocking { restarted.episodes.getEpisode(id) })
    }

    @Test fun discardingADraftDeletesItsClipsAndSavedScript() {
        goLive()
        failTtsRequestNumber.set(3)
        container.coordinator.start(request)
        assertEquals(GenerationStatus.FAILED, awaitTerminal())
        val id = container.coordinator.job.value!!.id
        assertEquals(2, draftClipFiles(id).size)

        runBlocking { container.coordinator.discardDraft(id) }

        assertTrue(draftClipFiles(id).isEmpty())
        assertNull(runBlocking { container.content.getScript(id) })
        assertNull(runBlocking { container.coordinator.unfinishedDraft() })
        assertFalse(runBlocking { container.coordinator.retryAudio(id) }) // nothing left to resume
    }

    @Test fun anExhaustedElevenLabsAccountStopsWithoutEverRetryingTheCall() {
        eleven.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                if (request.path!!.startsWith("/v2/voices")) return MockResponse().setBody(voicesPageJson(Triple("voiceHost", "Rachel", null)))
                ttsCalls += TtsCall("", "", "", null, null)
                return MockResponse().setResponseCode(402).setBody("""{"detail":{"code":"insufficient_credits"}}""")
            }
        }
        goLive()
        container.coordinator.start(request)
        assertEquals(GenerationStatus.FAILED, awaitTerminal())
        assertEquals(1, ttsCalls.size) // asked once, told "no credits", and stopped
        assertTrue(container.coordinator.job.value!!.error!!.contains("credits"))
        assertEquals(2, openAi.requestCount)
    }

    @Test fun aSecondGenerateWhileOneRunsIsRefusedAndMakesNoExtraRequests() {
        goLive()
        assertTrue(container.coordinator.start(request))
        assertFalse(container.coordinator.start(request))
        assertEquals(GenerationStatus.COMPLETED, awaitTerminal())
        assertEquals(2, openAi.requestCount) // exactly one run's worth
        assertEquals(14, ttsCalls.size)
    }
}
