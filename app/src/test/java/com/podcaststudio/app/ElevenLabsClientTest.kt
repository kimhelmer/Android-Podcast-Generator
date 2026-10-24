package com.podcaststudio.app

import com.podcaststudio.app.data.remote.ElevenLabsClient
import com.podcaststudio.app.data.remote.ElevenLabsClientFactory
import com.podcaststudio.app.data.remote.ElevenLabsErrorKind
import com.podcaststudio.app.data.remote.ElevenLabsException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.SocketPolicy
import okio.Buffer
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test

/** Local MockWebServer only: no real ElevenLabs traffic and no credits spent. */
class ElevenLabsClientTest {
    private lateinit var server: MockWebServer
    private val waits = mutableListOf<Long>()
    private var key: String? = FAKE_EL_KEY

    @Before fun setUp() {
        server = MockWebServer().apply { start() }
    }

    @After fun tearDown() = server.shutdown()

    private fun client(maxRetries: Int = 2, readTimeout: Long = 10): ElevenLabsClient = ElevenLabsClientFactory.create(
        keyProvider = { key },
        baseUrl = server.url("/").toString(),
        requireHttps = false,
        readTimeoutSeconds = readTimeout,
        maxRetries = maxRetries,
        backoff = { waits += it },
    )

    private val body = buildJsonObject { put("text", "Hej"); put("model_id", "eleven_multilingual_v2") }
    private val audio = pcmTone(500)

    private fun audioResponse(bytes: ByteArray = audio) =
        MockResponse().setHeader("Content-Type", "audio/pcm").setBody(Buffer().write(bytes))

    private fun failure(block: suspend () -> Unit): ElevenLabsException {
        try {
            runBlocking { block() }
        } catch (e: ElevenLabsException) {
            return e
        }
        fail("expected ElevenLabsException")
        throw IllegalStateException()
    }

    // ---- voices ----

    @Test fun voicesAreFetchedWithKeyHeaderAndMapped() {
        server.enqueue(
            MockResponse().setBody(
                voicesPageJson(
                    Triple("v1", "Rachel", "https://cdn.example.org/r.mp3"),
                    Triple("v2", "Adam", "http://insecure.example.org/a.mp3"),
                    Triple("v3", "Sky", null),
                ),
            ),
        )
        val voices = runBlocking { client().listVoices() }
        val req = server.takeRequest()
        assertEquals(FAKE_EL_KEY, req.getHeader("xi-api-key"))
        assertTrue(req.path!!.startsWith("/v2/voices"))
        assertTrue(req.path!!.contains("page_size=100"))

        assertEquals(listOf("Rachel", "Adam", "Sky"), voices.map { it.name })
        assertEquals("https://cdn.example.org/r.mp3", voices[0].previewUrl)
        assertNull("non-HTTPS previews are dropped", voices[1].previewUrl)
        assertNull(voices[2].previewUrl)
        assertEquals("british, female, narration", voices[0].description)
    }

    @Test fun voiceListFollowsPagination() {
        server.enqueue(MockResponse().setBody(voicesPageJson(Triple("a", "A", null), hasMore = true, next = "tok2")))
        server.enqueue(MockResponse().setBody(voicesPageJson(Triple("b", "B", null))))
        val voices = runBlocking { client().listVoices() }
        assertEquals(listOf("a", "b"), voices.map { it.id })
        server.takeRequest()
        assertTrue(server.takeRequest().path!!.contains("next_page_token=tok2"))
    }

    @Test fun garbageVoiceListIsMalformed() {
        server.enqueue(MockResponse().setBody("<html>"))
        assertEquals(ElevenLabsErrorKind.MALFORMED, failure { client().listVoices() }.kind)
    }

    // ---- speech ----

    @Test fun speechRequestShape() {
        server.enqueue(audioResponse())
        val pcm = runBlocking { client().synthesize("voice123", body) }
        assertArrayEquals(audio, pcm)

        val req = server.takeRequest()
        assertEquals("POST", req.method)
        assertTrue(req.path!!.startsWith("/v1/text-to-speech/voice123"))
        assertTrue(req.path!!.contains("output_format=pcm_24000"))
        assertEquals(FAKE_EL_KEY, req.getHeader("xi-api-key"))
        assertTrue(req.getHeader("Content-Type")!!.startsWith("application/json"))
        assertTrue(req.body.readUtf8().contains("\"text\":\"Hej\""))
    }

    @Test fun missingKeyFailsBeforeAnyRequest() {
        key = null
        assertEquals(ElevenLabsErrorKind.NO_API_KEY, failure { client().synthesize("v", body) }.kind)
        assertEquals(0, server.requestCount)
    }

    @Test fun invalidKeyIsAuthAndNotRetried() {
        server.enqueue(MockResponse().setResponseCode(401).setBody("""{"detail":{"type":"authentication_error","code":"invalid_api_key","message":"bad $FAKE_EL_KEY"}}"""))
        val e = failure { client().synthesize("v", body) }
        assertEquals(ElevenLabsErrorKind.AUTH, e.kind)
        assertFalse(e.message!!.contains(FAKE_EL_KEY))
        assertEquals(1, server.requestCount)
    }

    @Test fun insufficientCreditsAndLegacyQuotaStatusAreQuota() {
        server.enqueue(MockResponse().setResponseCode(402).setBody("""{"detail":{"code":"insufficient_credits"}}"""))
        assertEquals(ElevenLabsErrorKind.QUOTA, failure { client().synthesize("v", body) }.kind)
        server.enqueue(MockResponse().setResponseCode(401).setBody("""{"detail":{"status":"quota_exceeded","message":"x"}}"""))
        assertEquals(ElevenLabsErrorKind.QUOTA, failure { client().synthesize("v", body) }.kind)
        assertEquals(2, server.requestCount)
    }

    @Test fun forbiddenExplainsPermissions() {
        server.enqueue(MockResponse().setResponseCode(403).setBody("{}"))
        val e = failure { client().synthesize("v", body) }
        assertEquals(ElevenLabsErrorKind.PERMISSION, e.kind)
        assertTrue(e.message!!.contains("permissions"))
    }

    @Test fun notFoundDistinguishesVoiceAndModel() {
        server.enqueue(MockResponse().setResponseCode(404).setBody("""{"detail":{"code":"voice_not_found"}}"""))
        assertEquals(ElevenLabsErrorKind.VOICE_NOT_FOUND, failure { client().synthesize("v", body) }.kind)
        server.enqueue(MockResponse().setResponseCode(404).setBody("""{"detail":{"code":"model_not_found"}}"""))
        assertEquals(ElevenLabsErrorKind.MODEL_NOT_FOUND, failure { client().synthesize("v", body) }.kind)
    }

    @Test fun textTooLongAndValidationErrors() {
        server.enqueue(MockResponse().setResponseCode(400).setBody("""{"detail":{"code":"text_too_long"}}"""))
        assertEquals(ElevenLabsErrorKind.TEXT_TOO_LONG, failure { client().synthesize("v", body) }.kind)
        server.enqueue(MockResponse().setResponseCode(422).setBody("""{"detail":[{"loc":["body","text"],"msg":"field required","type":"missing"}]}"""))
        val e = failure { client().synthesize("v", body) }
        assertEquals(ElevenLabsErrorKind.BAD_REQUEST, e.kind)
        assertTrue(e.message!!.contains("field required"))
    }

    @Test fun concurrencyLimitRetriesWithBackoffThenSucceeds() {
        server.enqueue(MockResponse().setResponseCode(429).setBody("""{"detail":{"code":"concurrent_limit_exceeded"}}"""))
        server.enqueue(MockResponse().setResponseCode(429).setBody("""{"detail":{"code":"system_busy"}}"""))
        server.enqueue(audioResponse())
        val pcm = runBlocking { client().synthesize("v", body) }
        assertArrayEquals(audio, pcm)
        assertEquals(listOf(2000L, 4000L), waits)
    }

    @Test fun persistentRateLimitGivesUpAfterBoundedRetries() {
        repeat(3) { server.enqueue(MockResponse().setResponseCode(429).setBody("{}")) }
        assertEquals(ElevenLabsErrorKind.RATE_LIMIT, failure { client(maxRetries = 2).synthesize("v", body) }.kind)
        assertEquals(3, server.requestCount)
    }

    @Test fun serverErrorsAreNeverRetriedBecauseTheSpeechMayAlreadyBeBilled() {
        for (code in listOf(500, 502, 503)) {
            val before = server.requestCount
            server.enqueue(MockResponse().setResponseCode(code))
            val e = failure { client(maxRetries = 5).synthesize("v", body) }
            assertEquals("HTTP $code", ElevenLabsErrorKind.SERVER, e.kind)
            assertEquals("HTTP $code must not be retried", before + 1, server.requestCount)
        }
        assertTrue(waits.isEmpty())
    }

    @Test fun aTruncatedDownloadIsReportedNotRetried() {
        // The server promises the full body but hangs up part-way through. The audio was generated (and billed),
        // so asking again silently would pay twice: the caller decides via "Retry audio".
        server.enqueue(
            MockResponse().setHeader("Content-Type", "audio/pcm")
                .setBody(Buffer().write(audio)).setSocketPolicy(SocketPolicy.DISCONNECT_DURING_RESPONSE_BODY),
        )
        server.enqueue(audioResponse()) // must stay unused
        assertEquals(ElevenLabsErrorKind.INCOMPLETE_AUDIO, failure { client(maxRetries = 5).synthesize("v", body) }.kind)
        assertEquals(1, server.requestCount)
    }

    @Test fun cancellingDuringABackoffSendsNoFurtherRequest() {
        server.enqueue(MockResponse().setResponseCode(429).setBody("""{"detail":{"code":"system_busy"}}"""))
        server.enqueue(audioResponse()) // must stay unused
        val entered = java.util.concurrent.CountDownLatch(1)
        val c = ElevenLabsClientFactory.create(
            keyProvider = { key },
            baseUrl = server.url("/").toString(),
            requireHttps = false,
            maxRetries = 3,
            backoff = { entered.countDown(); kotlinx.coroutines.delay(60_000) },
        )
        runBlocking {
            val job = launch(Dispatchers.IO) { runCatching { c.synthesize("v", body) } }
            assertTrue("never reached the backoff", entered.await(10, java.util.concurrent.TimeUnit.SECONDS))
            job.cancelAndJoin()
        }
        assertEquals(1, server.requestCount)
    }

    @Test fun cancellingWhileSpeechIsBeingGeneratedDoesNotCauseASecondRequest() {
        server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE))
        server.enqueue(audioResponse()) // must stay unused
        val c = client(maxRetries = 3, readTimeout = 30)
        runBlocking {
            val job = launch(Dispatchers.IO) { runCatching { c.synthesize("v", body) } }
            withTimeout(10_000) { while (server.requestCount == 0) kotlinx.coroutines.delay(10) }
            job.cancelAndJoin()
        }
        Thread.sleep(300)
        assertEquals(1, server.requestCount)
    }

    @Test fun jsonOrEmptyBodyWith200IsNotAccepted() {
        server.enqueue(MockResponse().setHeader("Content-Type", "application/json").setBody("""{"oops":true}"""))
        assertEquals(ElevenLabsErrorKind.MALFORMED, failure { client().synthesize("v", body) }.kind)
        server.enqueue(MockResponse().setHeader("Content-Type", "audio/pcm").setBody(""))
        assertEquals(ElevenLabsErrorKind.INCOMPLETE_AUDIO, failure { client(maxRetries = 0).synthesize("v", body) }.kind)
    }

    @Test fun slowResponseTimesOutWithoutRetry() {
        server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE))
        assertEquals(ElevenLabsErrorKind.TIMEOUT, failure { client(readTimeout = 1).synthesize("v", body) }.kind)
        assertEquals(1, server.requestCount)
    }

    @Test fun deadServerIsNetworkError() {
        val dead = ElevenLabsClientFactory.create({ key }, "http://127.0.0.1:1/", requireHttps = false, maxRetries = 0)
        assertEquals(ElevenLabsErrorKind.NETWORK, failure { dead.synthesize("v", body) }.kind)
    }

    @Test fun factoryRefusesNonHttps() {
        try {
            ElevenLabsClientFactory.create({ key }, baseUrl = "http://api.elevenlabs.io/")
            fail()
        } catch (_: IllegalArgumentException) {
        }
    }
}
