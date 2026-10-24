package com.podcaststudio.app.data.remote

import com.podcaststudio.app.domain.AiSettingsStore
import com.podcaststudio.app.domain.AppJson
import com.podcaststudio.app.domain.IssueSeverity
import com.podcaststudio.app.domain.Language
import com.podcaststudio.app.domain.PodcastRequest
import com.podcaststudio.app.domain.PodcastScript
import com.podcaststudio.app.domain.ResearchProvider
import com.podcaststudio.app.domain.ResearchResult
import com.podcaststudio.app.domain.ScriptGenerator
import com.podcaststudio.app.domain.ScriptIssue
import com.podcaststudio.app.domain.ScriptSegment
import com.podcaststudio.app.domain.ScriptValidator
import com.podcaststudio.app.domain.ScriptValidator.errors
import com.podcaststudio.app.domain.ScriptValidator.warnings
import com.podcaststudio.app.domain.Speaker
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

private fun Language.englishName() = when (this) {
    Language.DANISH -> "Danish"
    Language.ENGLISH -> "English"
}

private fun isoDate(millis: Long): String =
    SimpleDateFormat("yyyy-MM-dd", Locale.US).apply { timeZone = TimeZone.getTimeZone("UTC") }.format(Date(millis))

/** Research stage: one Responses call with the built-in web search tool. */
class OpenAiResearchProvider(
    private val client: OpenAiClient,
    private val settings: AiSettingsStore,
    private val clock: () -> Long = System::currentTimeMillis,
) : ResearchProvider {

    override suspend fun research(request: PodcastRequest): ResearchResult {
        val now = clock()
        val model = settings.get().researchModel
        val lang = request.language.englishName()
        val points = request.durationMinutes * 3

        val instructions = """
            You are a careful research assistant preparing material for a podcast. Today's date is ${isoDate(now)}.
            Use web search to find accurate, current information about the user's topic. Prefer credible primary sources, official
            bodies, established publications and recent material where recency matters.
            Write the notes in $lang, as plain text with these headings: Key facts, Background, Recent developments,
            Examples and stories, Uncertain or disputed.
            Aim for roughly $points distinct, specific points in total.
            Label every claim: put "[verified]" at the end of facts supported by a source you found, and "[unverified]" for anything
            you could not confirm, and put speculation or disputed claims only under "Uncertain or disputed".
            Never invent sources, quotes, statistics or URLs. If you cannot find reliable information on part of the topic, say so.
            Do not write URLs in the notes; sources are collected separately from your search citations.
        """.trimIndent()

        val body = buildJsonObject {
            put("model", model)
            put("instructions", instructions)
            put("input", "Topic: ${request.topic.trim()}")
            putJsonArray("tools") { add(buildJsonObject { put("type", "web_search") }) }
            put("tool_choice", "required")
            put("max_output_tokens", 12_000)
            put("store", false)
        }
        val result = client.createResponse(body)

        val warnings = buildList {
            if (result.sources.isEmpty()) add("No web sources were cited; treat these notes as unverified.")
        }
        return ResearchResult(
            topic = request.topic.trim(),
            language = request.language,
            notes = result.text.trim(),
            sources = result.sources,
            researchedAt = now,
            model = result.model.ifBlank { model },
            warnings = warnings,
            inputTokens = result.inputTokens,
            outputTokens = result.outputTokens,
        )
    }
}

/** Script stage: strict JSON-schema output, validated locally, with one repair attempt. */
class OpenAiScriptGenerator(
    private val client: OpenAiClient,
    private val settings: AiSettingsStore,
    private val clock: () -> Long = System::currentTimeMillis,
    private val maxRepairAttempts: Int = 1,
) : ScriptGenerator {

    override suspend fun generate(request: PodcastRequest, research: ResearchResult): PodcastScript {
        val s = settings.get()
        val targetWords = request.durationMinutes * s.wordsPerMinute
        var feedback: List<ScriptIssue> = emptyList()
        var attempt = 0
        var inTokens = 0
        var outTokens = 0
        while (true) {
            val result = client.createResponse(buildBody(request, research, s.scriptModel, targetWords, feedback))
            inTokens += result.inputTokens
            outTokens += result.outputTokens
            val script = parse(result, request, research, s.scriptModel, targetWords, s.wordsPerMinute)
            val issues = ScriptValidator.validate(script, request.language, targetWords)
            if (issues.errors().isEmpty()) {
                // A rewrite is a second paid call, so it is recorded rather than done silently.
                val notes = issues.warnings().map { it.message } +
                    (if (attempt > 0) listOf("The first script failed validation and was rewritten once (one extra OpenAI call).") else emptyList())
                return script.copy(warnings = notes, inputTokens = inTokens, outputTokens = outTokens)
            }
            if (attempt >= maxRepairAttempts) {
                throw OpenAiException(
                    OpenAiErrorKind.INVALID_SCRIPT,
                    "The generated script failed validation: " + issues.errors().take(3).joinToString(" ") { it.message },
                )
            }
            feedback = issues.filter { it.severity == IssueSeverity.ERROR }
            attempt++
        }
    }

    internal fun buildBody(
        request: PodcastRequest,
        research: ResearchResult,
        model: String,
        targetWords: Int,
        feedback: List<ScriptIssue>,
    ): JsonObject {
        val lang = request.language.englishName()
        val minSegments = maxOf(8, targetWords / 80)
        val maxSegments = maxOf(minSegments + 4, targetWords / 25)

        val instructions = """
            You write scripts for a two-person conversational podcast. The speakers are HOST and GUEST only: no narrator, no third voice.
            Write everything the speakers say in natural, spoken $lang, as if talking, not reading an essay.
            Rules:
            - Speakers strictly alternate: HOST, GUEST, HOST, GUEST, ... and the first segment is HOST.
            - Use only facts from the research notes. Do not add new facts. Where the notes say something is unverified or disputed, have the speakers say so plainly.
            - Structure: a short introduction by the HOST, then 2 to 4 logical sections with natural questions, explanations and concrete examples, then a closing summary.
            - The "section" field names the section of each segment (use "Introduction" for the opening and "Closing summary" for the end).
            - Spoken text is plain sentences only: no URLs, no citation markers like [1], no markdown, no stage directions or sound effects in the text.
            - "delivery" is an optional one or two word performance hint such as "thoughtful" or "enthusiastic" (or null). It is never spoken.
            - Total spoken length about $targetWords words (between ${(targetWords * 0.9).toInt()} and ${(targetWords * 1.1).toInt()}), in $minSegments to $maxSegments segments, each at most 120 words.
            - "id" values are "s01", "s02", ... in order. "title" is a short episode title in $lang. "summary" is one or two sentences in $lang describing the episode.
        """.trimIndent()

        val sourceTitles = research.sources.withIndex().joinToString("\n") { (i, src) -> "${i + 1}. ${src.title}" }
        val input = buildString {
            appendLine("Topic: ${request.topic.trim()}")
            appendLine("Language: $lang")
            appendLine("Target length: ${request.durationMinutes} minutes (about $targetWords words)")
            appendLine()
            appendLine("Research notes (researched ${isoDate(research.researchedAt)}):")
            appendLine(research.notes)
            if (sourceTitles.isNotEmpty()) {
                appendLine()
                appendLine("Sources behind the notes (for your awareness; do not read them out as citations):")
                appendLine(sourceTitles)
            }
            if (feedback.isNotEmpty()) {
                appendLine()
                appendLine("Your previous attempt was rejected for these problems. Write the whole script again and fix them:")
                feedback.forEach { appendLine("- ${it.message}") }
            }
        }

        return buildJsonObject {
            put("model", model)
            put("instructions", instructions)
            put("input", input)
            put("max_output_tokens", 24_000)
            put("store", false)
            putJsonObject("text") {
                putJsonObject("format") {
                    put("type", "json_schema")
                    put("name", "podcast_script")
                    put("strict", true)
                    put("schema", scriptSchema)
                }
            }
        }
    }

    private fun parse(
        result: ResponsesResult,
        request: PodcastRequest,
        research: ResearchResult,
        requestedModel: String,
        targetWords: Int,
        wpm: Int,
    ): PodcastScript {
        val root = try {
            AppJson.parseToJsonElement(result.text).jsonObject
        } catch (e: Exception) {
            throw OpenAiException(OpenAiErrorKind.MALFORMED, "The script was not valid JSON.")
        }
        try {
            val segments = root.getValue("segments").jsonArray.map { el ->
                val o = el.jsonObject
                ScriptSegment(
                    id = o.getValue("id").jsonPrimitive.content,
                    speaker = Speaker.valueOf(o.getValue("speaker").jsonPrimitive.content),
                    text = o.getValue("text").jsonPrimitive.content.trim(),
                    delivery = o["delivery"]?.jsonPrimitive?.contentOrNull?.trim()?.takeIf { it.isNotEmpty() },
                    section = o["section"]?.jsonPrimitive?.contentOrNull.orEmpty(),
                )
            }
            val words = segments.sumOf { it.text.trim().split(Regex("\\s+")).count { w -> w.isNotEmpty() } }
            return PodcastScript(
                title = root.getValue("title").jsonPrimitive.content.trim(),
                summary = root.getValue("summary").jsonPrimitive.content.trim(),
                language = request.language,
                segments = segments,
                sources = research.sources,
                researchedAt = research.researchedAt,
                generatedAt = clock(),
                researchModel = research.model,
                scriptModel = result.model.ifBlank { requestedModel },
                targetWords = targetWords,
                estimatedDurationSeconds = (words * 60.0 / wpm).toInt(),
            )
        } catch (e: Exception) {
            throw OpenAiException(OpenAiErrorKind.MALFORMED, "The script JSON did not match the expected structure.")
        }
    }

    companion object {
        /** Strict-mode schema: every object closed, every key required, optional values as null unions. */
        val scriptSchema: JsonObject = buildJsonObject {
            put("type", "object")
            put("additionalProperties", false)
            putJsonArray("required") { add("title"); add("summary"); add("segments") }
            putJsonObject("properties") {
                putJsonObject("title") { put("type", "string") }
                putJsonObject("summary") { put("type", "string") }
                putJsonObject("segments") {
                    put("type", "array")
                    putJsonObject("items") {
                        put("type", "object")
                        put("additionalProperties", false)
                        putJsonArray("required") { add("id"); add("section"); add("speaker"); add("text"); add("delivery") }
                        putJsonObject("properties") {
                            putJsonObject("id") { put("type", "string") }
                            putJsonObject("section") { put("type", "string") }
                            putJsonObject("speaker") {
                                put("type", "string")
                                putJsonArray("enum") { add("HOST"); add("GUEST") }
                            }
                            putJsonObject("text") { put("type", "string") }
                            putJsonObject("delivery") {
                                putJsonArray("type") { add("string"); add("null") }
                            }
                        }
                    }
                }
            }
        }
    }
}
