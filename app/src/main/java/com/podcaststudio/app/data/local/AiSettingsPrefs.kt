package com.podcaststudio.app.data.local

import android.content.Context
import com.podcaststudio.app.domain.AiSettings
import com.podcaststudio.app.domain.AiSettingsStore

class SharedPrefsAiSettingsStore(context: Context) : AiSettingsStore {
    private val prefs = context.applicationContext.getSharedPreferences("ai_settings", Context.MODE_PRIVATE)

    override fun get(): AiSettings {
        val d = AiSettings()
        return AiSettings(
            useLiveOpenAi = prefs.getBoolean("live", d.useLiveOpenAi),
            researchModel = prefs.getString("research_model", null)?.takeIf { it.isNotBlank() } ?: d.researchModel,
            scriptModel = prefs.getString("script_model", null)?.takeIf { it.isNotBlank() } ?: d.scriptModel,
            wordsPerMinute = prefs.getInt("wpm", d.wordsPerMinute).coerceIn(AiSettings.WPM_RANGE),
            useLiveElevenLabs = prefs.getBoolean("live_tts", d.useLiveElevenLabs),
            ttsModel = prefs.getString("tts_model", null)?.takeIf { it.isNotBlank() } ?: d.ttsModel,
            maxTtsCharacters = prefs.getInt("max_tts_chars", d.maxTtsCharacters)
                .coerceIn(AiSettings.MAX_TTS_CHARACTERS_RANGE),
        )
    }

    override fun set(settings: AiSettings) {
        prefs.edit()
            .putBoolean("live", settings.useLiveOpenAi)
            .putString("research_model", settings.researchModel.trim())
            .putString("script_model", settings.scriptModel.trim())
            .putInt("wpm", settings.wordsPerMinute.coerceIn(AiSettings.WPM_RANGE))
            .putBoolean("live_tts", settings.useLiveElevenLabs)
            .putString("tts_model", settings.ttsModel.trim())
            .putInt("max_tts_chars", settings.maxTtsCharacters.coerceIn(AiSettings.MAX_TTS_CHARACTERS_RANGE))
            .apply()
    }
}
