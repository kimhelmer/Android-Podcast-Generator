package com.podcaststudio.app.domain

/** Non-secret generation settings. API keys live in the Keystore-backed SecretStore instead. */
data class AiSettings(
    /** Off by default so nothing is sent to OpenAI (or billed) until the user opts in. */
    val useLiveOpenAi: Boolean = false,
    val researchModel: String = DEFAULT_MODEL,
    val scriptModel: String = DEFAULT_MODEL,
    val wordsPerMinute: Int = DEFAULT_WPM,
    /** Off by default: ElevenLabs bills per character of speech. */
    val useLiveElevenLabs: Boolean = false,
    val ttsModel: String = DEFAULT_TTS_MODEL,
    /** Hard cap on characters sent to text-to-speech for one episode. */
    val maxTtsCharacters: Int = DEFAULT_MAX_TTS_CHARACTERS,
) {
    companion object {
        /** Model ids change often; this is only a starting point and is editable in Settings. */
        const val DEFAULT_MODEL = "gpt-6.1-sol"
        const val DEFAULT_WPM = 150
        val WPM_RANGE = 100..220

        /** Documented to support Danish and English, so it is a safe default. */
        const val DEFAULT_TTS_MODEL = "eleven_multilingual_v2"
        const val DEFAULT_MAX_TTS_CHARACTERS = 20_000
        val MAX_TTS_CHARACTERS_RANGE = 1_000..100_000

        /** Rough characters per spoken word including spaces and punctuation (Danish runs longer than English). */
        const val CHARS_PER_WORD = 6.5
    }

    /** Characters of speech an episode of [minutes] is expected to need. */
    fun estimatedCharacters(minutes: Int): Int = (minutes * wordsPerMinute * CHARS_PER_WORD).toInt()
}

interface AiSettingsStore {
    fun get(): AiSettings
    fun set(settings: AiSettings)
}

class InMemoryAiSettingsStore(private var current: AiSettings = AiSettings()) : AiSettingsStore {
    override fun get() = current
    override fun set(settings: AiSettings) { current = settings }
}
