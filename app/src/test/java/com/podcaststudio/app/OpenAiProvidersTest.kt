package com.podcaststudio.app

import com.podcaststudio.app.data.remote.OpenAiClientFactory
import com.podcaststudio.app.data.remote.OpenAiErrorKind
import com.podcaststudio.app.data.remote.OpenAiException
import com.podcaststudio.app.data.remote.OpenAiResearchProvider
import com.podcaststudio.app.data.remote.OpenAiScriptGenerator
import com.podcaststudio.app.domain.AiSettings
import com.podcaststudio.app.domain.InMemoryAiSettingsStore
import com.podcaststudio.app.domain.Language
import com.podcaststudio.app.domain.Speaker
import com.podcaststudio.app.domain.alternatesHostGuest
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.Json
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test

class OpenAiProvidersTest {
    private lateinit var server: MockWebServer
    private val settings = InMemoryAiSettingsStore(AiSettings(researchModel = "research-model", scriptModel = "script-model", wordsPerMinute = 150))

    @Before fun setUp() {
        server = MockWebServer().apply { start() }
    }

    @After fun tearDown() = server.shutdown()

    private val client by lazy {
        OpenAiClientFactory.create(
            keyProvider = { FAKE_KEY },
            baseUrl = server.url("/").toString(),
            requireHttps = false,
            maxRetries = 0,
        )
    }

    private fun lastBody(): JsonObject = Json.parseToJsonElement(server.takeRequest().body.readUtf8()).jsonObject

    // ---- research ----

    @Test fun researchUsesWebSearchToolAndRequestedModelAndLanguage() {
        server.enqueue(MockResponse().setBody(responsesJson("Facts [verified]", listOf("Bee study" to "https://example.org/bees"))))
        val provider = OpenAiResearchProvider(client, settings, clock = { 1_700_000_000_000 })
        val r = runBlocking { provider.research(enRequest.copy(language = Language.DANISH)) }

        val body = lastBody()
        assertEquals("research-model", body["model"]!!.jsonPrimitive.content)
        assertEquals("web_search", body["tools"]!!.jsonArray[0].jsonObject["type"]!!.jsonPrimitive.content)
        assertEquals("required", body["tool_choice"]!!.jsonPrimitive.content)
        assertFalse(body["store"]!!.jsonPrimitive.boolean)
        assertTrue(body["instructions"]!!.jsonPrimitive.content.contains("Danish"))
        assertTrue(body["instructions"]!!.jsonPrimitive.content.contains("2023-11-14")) // research date is stated
        assertTrue(body["input"]!!.jsonPrimitive.content.contains("honeybees"))

        assertEquals("Facts [verified]", r.notes)
        assertEquals(1_700_000_000_000, r.researchedAt)
        assertEquals("gpt-test-2026", r.model)
        assertEquals(Language.DANISH, r.language)
        assertEquals("https://example.org/bees", r.sources.single().url)
        assertTrue(r.warnings.isEmpty())
    }

    @Test fun researchWithoutCitationsIsFlaggedNotInvented() {
        server.enqueue(MockResponse().setBody(responsesJson("Some notes mentioning https://made-up.example/x")))
        val r = runBlocking { OpenAiResearchProvider(client, settings).research(enRequest) }
        assertTrue(r.sources.isEmpty()) // URLs in prose are never promoted to sources
        assertTrue(r.warnings.single().contains("No web sources"))
    }

    @Test fun researchErrorsPropagateAsOpenAiException() {
        server.enqueue(MockResponse().setResponseCode(401).setBody("{}"))
        try {
            runBlocking { OpenAiResearchProvider(client, settings).research(enRequest) }
            fail()
        } catch (e: OpenAiException) {
            assertEquals(OpenAiErrorKind.AUTH, e.kind)
        }
    }

    // ---- script ----

    private fun generator(repairs: Int = 1) = OpenAiScriptGenerator(client, settings, clock = { 5L }, maxRepairAttempts = repairs)

    @Test fun scriptRequestIsStrictJsonSchemaWithoutTools() {
        server.enqueue(MockResponse().setBody(responsesJson(scriptJson())))
        runBlocking { generator().generate(enRequest, sampleResearch()) }
        val body = lastBody()

        assertEquals("script-model", body["model"]!!.jsonPrimitive.content)
        assertFalse("script call must not use tools", body.containsKey("tools"))
        val format = body["text"]!!.jsonObject["format"]!!.jsonObject
        assertEquals("json_schema", format["type"]!!.jsonPrimitive.content)
        assertTrue(format["strict"]!!.jsonPrimitive.boolean)
        val schema = format["schema"]!!.jsonObject
        assertFalse(schema["additionalProperties"]!!.jsonPrimitive.boolean)
        val item = schema["properties"]!!.jsonObject["segments"]!!.jsonObject["items"]!!.jsonObject
        assertFalse(item["additionalProperties"]!!.jsonPrimitive.boolean)
        val props = item["properties"]!!.jsonObject.keys
        assertEquals(props, item["required"]!!.jsonArray.map { it.jsonPrimitive.content }.toSet()) // strict: all keys required
        val speakers = item["properties"]!!.jsonObject["speaker"]!!.jsonObject["enum"]!!.jsonArray.map { it.jsonPrimitive.content }
        assertEquals(listOf("HOST", "GUEST"), speakers)
    }

    @Test fun scriptPromptCarriesLanguageDurationAndResearch() {
        server.enqueue(MockResponse().setBody(responsesJson(scriptJson())))
        runBlocking { generator().generate(enRequest.copy(durationMinutes = 10), sampleResearch()) }
        val body = lastBody()
        val instructions = body["instructions"]!!.jsonPrimitive.content
        val input = body["input"]!!.jsonPrimitive.content
        assertTrue(instructions.contains("1500 words")) // 10 min x 150 wpm
        assertTrue(instructions.contains("HOST and GUEST only"))
        assertTrue(input.contains("Language: English"))
        assertTrue(input.contains("Bees use a waggle dance"))
        assertTrue(input.contains("Bee study"))
        assertFalse("sources are not handed over as URLs to quote", input.contains("https://example.org"))
    }

    @Test fun validScriptIsParsedAndCarriesVerifiedSourcesNotModelOnes() {
        server.enqueue(MockResponse().setBody(responsesJson(scriptJson(), model = "script-model-2026")))
        val research = sampleResearch()
        val s = runBlocking { generator().generate(enRequest, research) }

        assertEquals("The Waggle Dance", s.title)
        assertEquals(14, s.segments.size)
        assertTrue(s.segments.alternatesHostGuest())
        assertEquals(Speaker.HOST, s.segments.first().speaker)
        assertEquals("s01", s.segments.first().id)
        assertEquals("thoughtful", s.segments.first().delivery)
        assertNotNull(s.segments.first { it.delivery == null })
        assertEquals(research.sources, s.sources)
        assertEquals(research.researchedAt, s.researchedAt)
        assertEquals("research-model", s.researchModel)
        assertEquals("script-model-2026", s.scriptModel)
        assertEquals(750, s.targetWords)
        assertEquals(756, s.wordCount)
        assertEquals(756 * 60 / 150, s.estimatedDurationSeconds)
        assertEquals(1, server.requestCount)
    }

    @Test fun invalidScriptTriggersOneRepairWithFeedbackThenSucceeds() {
        server.enqueue(MockResponse().setBody(responsesJson(scriptJson(alternate = false))))
        server.enqueue(MockResponse().setBody(responsesJson(scriptJson())))
        val s = runBlocking { generator().generate(enRequest, sampleResearch()) }
        assertEquals(2, server.requestCount)
        server.takeRequest() // first
        val repairInput = Json.parseToJsonElement(server.takeRequest().body.readUtf8()).jsonObject["input"]!!.jsonPrimitive.content
        assertTrue(repairInput.contains("previous attempt was rejected"))
        assertTrue(repairInput.contains("alternate"))
        assertTrue(s.segments.alternatesHostGuest())
    }

    @Test fun scriptStillInvalidAfterRepairFailsWithoutSaving() {
        server.enqueue(MockResponse().setBody(responsesJson(scriptJson(alternate = false))))
        server.enqueue(MockResponse().setBody(responsesJson(scriptJson(alternate = false))))
        try {
            runBlocking { generator().generate(enRequest, sampleResearch()) }
            fail()
        } catch (e: OpenAiException) {
            assertEquals(OpenAiErrorKind.INVALID_SCRIPT, e.kind)
            assertTrue(e.message!!.contains("alternate"))
        }
        assertEquals(2, server.requestCount) // exactly one repair, no runaway spending
    }

    @Test fun markupOrUrlInSpokenTextIsRejected() {
        server.enqueue(MockResponse().setBody(responsesJson(scriptJson(firstText = "See https://example.org [1] for **details**"))))
        server.enqueue(MockResponse().setBody(responsesJson(scriptJson(firstText = "See https://example.org [1] for **details**"))))
        try {
            runBlocking { generator().generate(enRequest, sampleResearch()) }
            fail()
        } catch (e: OpenAiException) {
            assertEquals(OpenAiErrorKind.INVALID_SCRIPT, e.kind)
        }
    }

    @Test fun lengthWithinToleranceHasNoWarning() {
        server.enqueue(MockResponse().setBody(responsesJson(scriptJson(segments = 14, wordsEach = 45)))) // 630 of 750 = 84%
        val r = runBlocking { generator().generate(enRequest, sampleResearch()) }
        assertEquals(1, server.requestCount)
        assertTrue(r.warnings.isEmpty())
    }

    @Test fun moderatelyShortScriptIsAcceptedWithWarningAndNoRepair() {
        server.enqueue(MockResponse().setBody(responsesJson(scriptJson(segments = 14, wordsEach = 38)))) // 532 = 71%
        val r = runBlocking { generator().generate(enRequest, sampleResearch()) }
        assertEquals(1, server.requestCount)
        assertTrue(r.warnings.single().contains("shorter than requested"))
    }

    @Test fun grosslyShortScriptIsRepaired() {
        server.enqueue(MockResponse().setBody(responsesJson(scriptJson(segments = 14, wordsEach = 20)))) // 280 = 37%
        server.enqueue(MockResponse().setBody(responsesJson(scriptJson())))
        val r = runBlocking { generator().generate(enRequest, sampleResearch()) }
        assertEquals(2, server.requestCount)
        // the extra paid call is recorded, never silent
        assertTrue(r.warnings.single().contains("rewritten once"))
    }

    @Test fun wrongLanguageScriptIsRejected() {
        val danishRequest = enRequest.copy(language = Language.DANISH)
        server.enqueue(MockResponse().setBody(responsesJson(scriptJson())))
        server.enqueue(MockResponse().setBody(responsesJson(scriptJson())))
        try {
            runBlocking { generator().generate(danishRequest, sampleResearch().copy(language = Language.DANISH)) }
            fail()
        } catch (e: OpenAiException) {
            assertTrue(e.message!!.contains("English"))
        }
    }

    @Test fun danishScriptForDanishRequestPasses() {
        server.enqueue(MockResponse().setBody(responsesJson(scriptJson(danish = true, title = "Bidansen"))))
        val s = runBlocking {
            generator().generate(enRequest.copy(language = Language.DANISH), sampleResearch().copy(language = Language.DANISH))
        }
        assertEquals(Language.DANISH, s.language)
    }

    @Test fun malformedScriptJsonIsReported() {
        server.enqueue(MockResponse().setBody(responsesJson("not json at all")))
        try {
            runBlocking { generator().generate(enRequest, sampleResearch()) }
            fail()
        } catch (e: OpenAiException) {
            assertEquals(OpenAiErrorKind.MALFORMED, e.kind)
        }
    }

    @Test fun scriptWithWrongStructureIsReported() {
        server.enqueue(MockResponse().setBody(responsesJson("""{"title":"x","summary":"y","segments":[{"id":"s1","speaker":"NARRATOR","text":"hi","section":"","delivery":null}]}""")))
        try {
            runBlocking { generator().generate(enRequest, sampleResearch()) }
            fail()
        } catch (e: OpenAiException) {
            assertEquals(OpenAiErrorKind.MALFORMED, e.kind) // a third speaker cannot sneak in
        }
    }
}
