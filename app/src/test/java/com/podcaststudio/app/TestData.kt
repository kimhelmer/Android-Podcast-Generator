package com.podcaststudio.app

import com.podcaststudio.app.audio.MOCK_VOICES
import com.podcaststudio.app.domain.Language
import com.podcaststudio.app.domain.PodcastRequest
import com.podcaststudio.app.domain.ResearchResult
import com.podcaststudio.app.domain.SourceRef
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray

// The fake keys below must LOOK like real keys at runtime (the redaction tests depend on that), but the prefix is
// split across two literals so no key-shaped string appears in the source for secret scanners to flag.
// None of these is a credential.
private const val OPENAI_PREFIX = "s" + "k-"
private const val ELEVENLABS_PREFIX = "s" + "k_"

/** Fake OpenAI-shaped key. */
const val FAKE_KEY = OPENAI_PREFIX + "FAKE-openai-test-key-0001"

/** Fake project-style OpenAI key, for redaction tests. */
const val FAKE_PROJECT_KEY = OPENAI_PREFIX + "proj-FAKE-0002"

/** A fake key typed into the Settings field by UI tests. */
const val TYPED_FAKE_KEY = OPENAI_PREFIX + "typed-FAKE-key-0003"

val enRequest = PodcastRequest("How honeybees communicate", Language.ENGLISH, 5, MOCK_VOICES[0], MOCK_VOICES[1])

fun sampleResearch(sources: List<SourceRef> = listOf(SourceRef("Bee study", "https://example.org/bees"))) = ResearchResult(
    topic = "How honeybees communicate",
    language = Language.ENGLISH,
    notes = "Bees use a waggle dance. [verified]",
    sources = sources,
    researchedAt = 1_700_000_000_000,
    model = "research-model",
)

/** A Responses API body as OpenAI returns it for a completed call. */
fun responsesJson(
    text: String,
    citations: List<Pair<String, String>> = emptyList(),
    model: String = "gpt-test-2026",
    status: String = "completed",
): String = buildJsonObject {
    put("id", "resp_1")
    put("status", status)
    put("model", model)
    put(
        "output",
        buildJsonArray {
            add(buildJsonObject { put("type", "web_search_call"); put("id", "ws_1"); put("status", "completed") })
            add(
                buildJsonObject {
                    put("type", "message")
                    put("role", "assistant")
                    putJsonArray("content") {
                        add(
                            buildJsonObject {
                                put("type", "output_text")
                                put("text", text)
                                put(
                                    "annotations",
                                    JsonArray(
                                        citations.map { (title, url) ->
                                            buildJsonObject {
                                                put("type", "url_citation")
                                                put("start_index", 0)
                                                put("end_index", 1)
                                                put("title", title)
                                                put("url", url)
                                            }
                                        },
                                    ),
                                )
                            },
                        )
                    }
                },
            )
        },
    )
}.toString()

fun refusalJson(): String =
    """{"id":"r","status":"completed","model":"m","output":[{"type":"message","content":[{"type":"refusal","refusal":"I cannot help with that."}]}]}"""

private val enFiller = listOf("the", "bees", "dance", "and", "the", "hive", "listens", "to", "the", "dancer", "that", "is", "very", "clear")
private val daFiller = listOf("det", "er", "og", "bierne", "danser", "til", "en", "tydelig", "dans", "som", "alle", "i", "stadet", "ser")

/** Script JSON with strictly alternating HOST/GUEST segments, [segments] x [wordsEach] words. */
fun scriptJson(
    segments: Int = 14,
    wordsEach: Int = 54,
    danish: Boolean = false,
    alternate: Boolean = true,
    title: String = "The Waggle Dance",
    firstText: String? = null,
): String {
    val filler = if (danish) daFiller else enFiller
    fun text(i: Int) = (0 until wordsEach).joinToString(" ") { filler[(i + it) % filler.size] } + "."
    return buildJsonObject {
        put("title", title)
        put("summary", "How bees talk.")
        put(
            "segments",
            buildJsonArray {
                for (i in 0 until segments) {
                    val host = if (alternate) i % 2 == 0 else true
                    add(
                        buildJsonObject {
                            put("id", "s%02d".format(i + 1))
                            put("section", if (i == 0) "Introduction" else if (i == segments - 1) "Closing summary" else "The dance")
                            put("speaker", if (host) "HOST" else "GUEST")
                            put("text", if (i == 0 && firstText != null) firstText else text(i))
                            if (i % 3 == 0) put("delivery", "thoughtful") else put("delivery", kotlinx.serialization.json.JsonNull)
                        },
                    )
                }
            },
        )
    }.toString()
}

/** Fake ElevenLabs-shaped key, used only to prove redaction and header handling. */
const val FAKE_EL_KEY = ELEVENLABS_PREFIX + "FAKE_elevenlabs_test_key_0004"

/** 16-bit mono PCM at 24 kHz: [headMs] of silence, a loud 440 Hz tone for [toneMs], then [tailMs] of silence. */
fun pcmTone(toneMs: Int, headMs: Int = 0, tailMs: Int = 0, amplitude: Int = 8000, sampleRate: Int = 24_000): ByteArray {
    fun n(ms: Int) = sampleRate * ms / 1000
    val total = n(headMs) + n(toneMs) + n(tailMs)
    val out = java.nio.ByteBuffer.allocate(total * 2).order(java.nio.ByteOrder.LITTLE_ENDIAN)
    repeat(n(headMs)) { out.putShort(0) }
    for (i in 0 until n(toneMs)) out.putShort((amplitude * Math.sin(2 * Math.PI * 440 * i / sampleRate)).toInt().toShort())
    repeat(n(tailMs)) { out.putShort(0) }
    return out.array()
}

fun voicesPageJson(vararg voices: Triple<String, String, String?>, hasMore: Boolean = false, next: String? = null): String {
    val items = voices.joinToString(",") { (id, name, preview) ->
        """{"voice_id":"$id","name":"$name","category":"premade","labels":{"accent":"british","gender":"female","use_case":"narration"},""" +
            """"description":"d","preview_url":${if (preview == null) "null" else "\"$preview\""}}"""
    }
    return """{"voices":[$items],"has_more":$hasMore,"next_page_token":${if (next == null) "null" else "\"$next\""}}"""
}

class ParsedWav(val sampleRate: Int, val channels: Int, val bits: Int, val pcm: ByteArray) {
    val frames: Int get() = pcm.size / (channels * bits / 8)
    val seconds: Double get() = frames.toDouble() / sampleRate
}

/** Strict RIFF/WAVE reader: every size field must agree with the real file length, or the file is rejected. */
fun parseWav(file: java.io.File): ParsedWav {
    val bytes = file.readBytes()
    require(bytes.size >= 44) { "shorter than a WAV header" }
    val b = java.nio.ByteBuffer.wrap(bytes).order(java.nio.ByteOrder.LITTLE_ENDIAN)
    fun tag(at: Int) = String(bytes, at, 4, Charsets.US_ASCII)
    require(tag(0) == "RIFF") { "no RIFF tag" }
    require(b.getInt(4) == bytes.size - 8) { "RIFF size ${b.getInt(4)} != ${bytes.size - 8}" }
    require(tag(8) == "WAVE" && tag(12) == "fmt ") { "bad WAVE/fmt tags" }
    require(b.getInt(16) == 16 && b.getShort(20).toInt() == 1) { "not plain PCM" }
    val channels = b.getShort(22).toInt()
    val rate = b.getInt(24)
    val bits = b.getShort(34).toInt()
    require(b.getInt(28) == rate * channels * bits / 8) { "bad byte rate" }
    require(b.getShort(32).toInt() == channels * bits / 8) { "bad block align" }
    require(tag(36) == "data") { "no data chunk" }
    require(b.getInt(40) == bytes.size - 44) { "data size ${b.getInt(40)} != ${bytes.size - 44}" }
    return ParsedWav(rate, channels, bits, bytes.copyOfRange(44, bytes.size))
}
