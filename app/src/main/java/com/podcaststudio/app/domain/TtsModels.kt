package com.podcaststudio.app.domain

/** What a text-to-speech model can do. Drives language filtering and which request features are used. */
data class TtsModelCaps(
    val id: String,
    val languages: Set<Language>,
    val maxCharsPerRequest: Int,
    /** Audio tags such as [thoughtful] are interpreted as delivery, not spoken. */
    val supportsAudioTags: Boolean,
    val supportsLanguageCode: Boolean,
    /** False for ids not in the table: capabilities are then conservative guesses. */
    val known: Boolean = true,
    val deprecated: Boolean = false,
)

object TtsModels {
    private val both = setOf(Language.DANISH, Language.ENGLISH)

    // Based on ElevenLabs' model documentation. Where the docs were silent the flag is left off.
    private val table = listOf(
        TtsModelCaps("eleven_multilingual_v2", both, 10_000, supportsAudioTags = false, supportsLanguageCode = false),
        TtsModelCaps("eleven_flash_v2_5", both, 40_000, supportsAudioTags = false, supportsLanguageCode = false),
        TtsModelCaps("eleven_turbo_v2_5", both, 40_000, supportsAudioTags = false, supportsLanguageCode = false, deprecated = true),
        TtsModelCaps("eleven_v3", both, 5_000, supportsAudioTags = true, supportsLanguageCode = true),
        TtsModelCaps("eleven_v4", both, 10_000, supportsAudioTags = false, supportsLanguageCode = true),
        TtsModelCaps("eleven_v4_turbo", both, 5_000, supportsAudioTags = true, supportsLanguageCode = true),
    ).associateBy { it.id }

    val knownIds: List<String> get() = table.keys.toList()

    fun capabilities(modelId: String): TtsModelCaps = table[modelId.trim()]
        ?: TtsModelCaps(modelId.trim(), both, 5_000, supportsAudioTags = false, supportsLanguageCode = false, known = false)

    fun describe(modelId: String): String {
        val c = capabilities(modelId)
        return when {
            !c.known -> "Unrecognised model: Danish/English support is assumed, delivery directions are ignored."
            c.deprecated -> "Deprecated by ElevenLabs; prefer eleven_flash_v2_5."
            c.supportsAudioTags -> "Supports Danish and English and delivery directions."
            else -> "Supports Danish and English. Delivery directions are not used with this model."
        }
    }
}

/** Builds the exact text sent to TTS. The segment text itself is never altered. */
object TtsText {
    private val tagSafe = Regex("[^\\p{L} ]")

    fun prepare(segment: ScriptSegment, caps: TtsModelCaps): String {
        val tag = if (caps.supportsAudioTags) sanitizeDelivery(segment.delivery) else null
        return if (tag == null) segment.text else "[$tag] ${segment.text}"
    }

    /** Delivery hints become a short lowercase audio tag, or nothing if they look unsafe. */
    fun sanitizeDelivery(delivery: String?): String? {
        val cleaned = delivery?.lowercase()?.replace(tagSafe, "")?.trim()?.replace(Regex("\\s+"), " ").orEmpty()
        if (cleaned.isEmpty() || cleaned.split(" ").size > 2 || cleaned.length > 24) return null
        return cleaned
    }
}
