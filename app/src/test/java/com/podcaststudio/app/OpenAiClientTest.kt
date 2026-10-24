package com.podcaststudio.app

import com.podcaststudio.app.data.remote.OpenAiClient
import com.podcaststudio.app.data.remote.OpenAiClientFactory
import com.podcaststudio.app.data.remote.OpenAiErrorKind
import com.podcaststudio.app.data.remote.OpenAiException
import com.podcaststudio.app.data.remote.redactSecrets
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
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test

/** Talks only to a local MockWebServer: no real OpenAI traffic. */
class OpenAiClientTest {
    private lateinit var server: MockWebServer
    private val waits = mutableListOf<Long>()
    private var key: String? = FAKE_KEY

    @Before fun setUp() {
        server = MockWebServer().apply { start() }
    }

    @After fun tearDown() = server.shutdown()

    private fun client(maxRetries: Int = 2, readTimeout: Long = 10): OpenAiClient = OpenAiClientFactory.create(
        keyProvider = { key },
        baseUrl = server.url("/").toString(),
        requireHttps = false,
        readTimeoutSeconds = readTimeout,
        maxRetries = maxRetries,
        backoff = { waits += it },
    )

    private val body = buildJsonObject { put("model", "m"); put("input", "hi") }

    private fun failure(block: suspend () -> Unit): OpenAiException {
        try {
            runBlocking { block() }
        } catch (e: OpenAiException) {
            return e
        }
        fail("expected OpenAiException")
        throw IllegalStateException()
    }

    @Test fun successReturnsTextAndDedupedCleanCitations() {
        server.enqueue(
            MockResponse().setBody(
                responsesJson(
                    "Hello world",
                    citations = listOf(
                        "Bee study" to "https://example.org/bees?utm_source=openai",
                        "Bee study again" to "https://example.org/bees?utm_source=openai",
                        "Other" to "https://example.org/other?id=3&utm_source=openai",
                        "Insecure" to "http://example.org/insecure",
                        "Junk" to "not a url",
                    ),
                ),
            ),
        )
        val r = runBlocking { client().createResponse(body) }
        assertEquals("Hello world", r.text)
        assertEquals("gpt-test-2026", r.model)
        assertEquals(
            listOf("https://example.org/bees", "https://example.org/other?id=3"),
            r.sources.map { it.url },
        )
        assertEquals("Bee study", r.sources[0].title)
    }

    @Test fun requestGoesToResponsesEndpointWithBearerKey() {
        server.enqueue(MockResponse().setBody(responsesJson("ok")))
        runBlocking { client().createResponse(body) }
        val req = server.takeRequest()
        assertEquals("/v1/responses", req.path)
        assertEquals("POST", req.method)
        assertEquals("Bearer $FAKE_KEY", req.getHeader("Authorization"))
        assertTrue(req.getHeader("Content-Type")!!.startsWith("application/json"))
    }

    @Test fun missingKeyFailsBeforeAnyNetworkRequest() {
        key = null
        val e = failure { client().createResponse(body) }
        assertEquals(OpenAiErrorKind.NO_API_KEY, e.kind)
        assertEquals(0, server.requestCount)
    }

    @Test fun unauthorizedMapsToAuthWithoutLeakingKey() {
        server.enqueue(
            MockResponse().setResponseCode(401)
                .setBody("""{"error":{"message":"Incorrect API key provided: $FAKE_KEY","code":"invalid_api_key"}}"""),
        )
        val e = failure { client().createResponse(body) }
        assertEquals(OpenAiErrorKind.AUTH, e.kind)
        assertFalse(e.message!!.contains(FAKE_KEY))
        assertFalse(e.message!!.contains("sk-"))
        assertEquals(1, server.requestCount) // auth errors are not retried
    }

    @Test fun badRequestMessageIsRedactedAndTruncated() {
        server.enqueue(
            MockResponse().setResponseCode(400)
                .setBody("""{"error":{"message":"Model does not support web_search. key=$FAKE_KEY ${"x".repeat(1000)}"}}"""),
        )
        val e = failure { client().createResponse(body) }
        assertEquals(OpenAiErrorKind.BAD_REQUEST, e.kind)
        assertTrue(e.message!!.contains("web_search"))
        assertFalse(e.message!!.contains(FAKE_KEY))
        assertTrue(e.message!!.length < 500)
    }

    @Test fun unknownModelMapsToModelUnavailable() {
        server.enqueue(MockResponse().setResponseCode(404).setBody("""{"error":{"message":"The model does not exist"}}"""))
        assertEquals(OpenAiErrorKind.MODEL_UNAVAILABLE, failure { client().createResponse(body) }.kind)
    }

    @Test fun rateLimitRetriesHonouringRetryAfterThenSucceeds() {
        server.enqueue(MockResponse().setResponseCode(429).setHeader("Retry-After", "3").setBody("""{"error":{"code":"rate_limit_exceeded"}}"""))
        server.enqueue(MockResponse().setBody(responsesJson("recovered")))
        val r = runBlocking { client().createResponse(body) }
        assertEquals("recovered", r.text)
        assertEquals(listOf(3000L), waits)
        assertEquals(2, server.requestCount)
    }

    @Test fun rateLimitGivesUpAfterMaxRetriesWithExponentialBackoff() {
        repeat(3) { server.enqueue(MockResponse().setResponseCode(429).setBody("{}")) }
        val e = failure { client(maxRetries = 2).createResponse(body) }
        assertEquals(OpenAiErrorKind.RATE_LIMIT, e.kind)
        assertEquals(3, server.requestCount)
        assertEquals(listOf(2000L, 4000L), waits)
    }

    @Test fun insufficientQuotaIsNotRetried() {
        server.enqueue(MockResponse().setResponseCode(429).setBody("""{"error":{"code":"insufficient_quota","message":"x"}}"""))
        val e = failure { client().createResponse(body) }
        assertEquals(OpenAiErrorKind.QUOTA, e.kind)
        assertEquals(1, server.requestCount)
    }

    @Test fun serverErrorsAreNeverRetriedBecauseTheWorkMayAlreadyBeBilled() {
        for (code in listOf(500, 502, 503, 504)) {
            server.enqueue(MockResponse().setResponseCode(code).setBody("oops"))
            server.enqueue(MockResponse().setBody(responsesJson("must not be requested")))
            val before = server.requestCount
            val e = failure { client(maxRetries = 5).createResponse(body) }
            assertEquals("HTTP $code", OpenAiErrorKind.SERVER, e.kind)
            assertEquals("HTTP $code must not be retried", before + 1, server.requestCount)
            server.takeRequest()
            // drain the unused queued success so the next iteration starts clean
            runBlocking { client().createResponse(body) }
            server.takeRequest()
        }
        assertTrue(waits.isEmpty()) // no backoff means no retry was even considered
    }

    @Test fun anIncompleteAnswerIsNotRetried() {
        server.enqueue(MockResponse().setBody("""{"status":"incomplete","incomplete_details":{"reason":"max_output_tokens"},"output":[]}"""))
        server.enqueue(MockResponse().setBody(responsesJson("must not be requested")))
        assertEquals(OpenAiErrorKind.INCOMPLETE, failure { client(maxRetries = 5).createResponse(body) }.kind)
        assertEquals(1, server.requestCount)
    }

    @Test fun cancellingDuringARateLimitBackoffSendsNoFurtherRequest() {
        server.enqueue(MockResponse().setResponseCode(429).setBody("{}"))
        server.enqueue(MockResponse().setBody(responsesJson("must not be requested")))
        val entered = java.util.concurrent.CountDownLatch(1)
        val c = OpenAiClientFactory.create(
            keyProvider = { key },
            baseUrl = server.url("/").toString(),
            requireHttps = false,
            maxRetries = 3,
            backoff = { entered.countDown(); kotlinx.coroutines.delay(60_000) }, // a real, cancellable wait
        )
        runBlocking {
            val job = launch(Dispatchers.IO) { runCatching { c.createResponse(body) } }
            assertTrue("never reached the backoff", entered.await(10, java.util.concurrent.TimeUnit.SECONDS))
            job.cancelAndJoin()
        }
        assertEquals(1, server.requestCount) // the retry that would have followed was never sent
    }

    @Test fun cancellingWhileTheRequestIsInFlightDoesNotCauseASecondRequest() {
        server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE))
        server.enqueue(MockResponse().setBody(responsesJson("must not be requested")))
        val c = client(maxRetries = 3, readTimeout = 30)
        runBlocking {
            val job = launch(Dispatchers.IO) { runCatching { c.createResponse(body) } }
            withTimeout(10_000) { while (server.requestCount == 0) kotlinx.coroutines.delay(10) }
            job.cancelAndJoin()
        }
        Thread.sleep(300)
        assertEquals(1, server.requestCount)
    }

    @Test fun slowResponseMapsToTimeoutAndIsNotRetried() {
        server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE))
        val e = failure { client(readTimeout = 1).createResponse(body) }
        assertEquals(OpenAiErrorKind.TIMEOUT, e.kind)
        assertEquals(1, server.requestCount)
    }

    @Test fun connectionFailureMapsToNetwork() {
        val dead = OpenAiClientFactory.create(
            keyProvider = { key },
            baseUrl = "http://127.0.0.1:1/",
            requireHttps = false,
            maxRetries = 0,
        )
        assertEquals(OpenAiErrorKind.NETWORK, failure { dead.createResponse(body) }.kind)
    }

    @Test fun incompleteForTokenLimitIsReported() {
        server.enqueue(
            MockResponse().setBody("""{"status":"incomplete","incomplete_details":{"reason":"max_output_tokens"},"output":[]}"""),
        )
        val e = failure { client().createResponse(body) }
        assertEquals(OpenAiErrorKind.INCOMPLETE, e.kind)
        assertTrue(e.message!!.contains("cut off"))
    }

    @Test fun refusalIsReportedNotParsedAsContent() {
        server.enqueue(MockResponse().setBody(refusalJson()))
        assertEquals(OpenAiErrorKind.REFUSAL, failure { client().createResponse(body) }.kind)
    }

    @Test fun failedStatusMapsToServerError() {
        server.enqueue(MockResponse().setBody("""{"status":"failed","error":{"message":"boom $FAKE_KEY"},"output":[]}"""))
        val e = failure { client(maxRetries = 0).createResponse(body) }
        assertEquals(OpenAiErrorKind.SERVER, e.kind)
        assertFalse(e.message!!.contains(FAKE_KEY))
    }

    @Test fun garbageAndEmptyAnswersAreMalformed() {
        server.enqueue(MockResponse().setBody("<html>not json</html>"))
        assertEquals(OpenAiErrorKind.MALFORMED, failure { client().createResponse(body) }.kind)
        server.enqueue(MockResponse().setBody("""{"status":"completed","output":[]}"""))
        assertEquals(OpenAiErrorKind.MALFORMED, failure { client().createResponse(body) }.kind)
    }

    @Test fun factoryRefusesNonHttpsBaseUrlByDefault() {
        try {
            OpenAiClientFactory.create(keyProvider = { key }, baseUrl = "http://api.openai.com/")
            fail("expected IllegalArgumentException")
        } catch (_: IllegalArgumentException) {
        }
        assertNotNull(OpenAiClientFactory.create(keyProvider = { key })) // default URL is https
    }

    @Test fun redactionRemovesKeyShapedStringsAndKnownKey() {
        val text = "bad key $FAKE_KEY and ${"s" + "k-proj-abcdEFGH1234"} and custom-secret-value-xyz"
        val out = redactSecrets(text, "custom-secret-value-xyz")
        assertFalse(out.contains("sk-"))
        assertFalse(out.contains("custom-secret"))
        assertTrue(out.contains("[redacted]"))
    }
}
