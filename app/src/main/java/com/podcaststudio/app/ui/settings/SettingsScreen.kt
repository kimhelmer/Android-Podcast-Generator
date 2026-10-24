package com.podcaststudio.app.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.podcaststudio.app.domain.AiSettings
import com.podcaststudio.app.domain.AiSettingsStore
import com.podcaststudio.app.domain.TtsModels
import com.podcaststudio.app.security.SecretKeyName
import com.podcaststudio.app.security.SecretStore
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

data class SettingsUiState(
    val saved: Map<SecretKeyName, Boolean> = emptyMap(),
    val input: Map<SecretKeyName, String> = emptyMap(),
    val ai: AiSettings = AiSettings(),
    val researchModelDraft: String = "",
    val scriptModelDraft: String = "",
    val wpmDraft: String = "",
    val aiMessage: String? = null,
    val ttsModelDraft: String = "",
    val maxCharsDraft: String = "",
    val ttsMessage: String? = null,
)

/** Holds typed input only until saved; stored secrets are never read back into UI state. */
class SettingsViewModel(
    private val store: SecretStore,
    private val aiStore: AiSettingsStore,
) : ViewModel() {
    private val _state = MutableStateFlow(initialState())
    val state: StateFlow<SettingsUiState> = _state.asStateFlow()

    private fun snapshot() = SecretKeyName.entries.associateWith { store.has(it) }

    private fun initialState(): SettingsUiState {
        val ai = aiStore.get()
        return SettingsUiState(
            saved = snapshot(),
            ai = ai,
            researchModelDraft = ai.researchModel,
            scriptModelDraft = ai.scriptModel,
            wpmDraft = ai.wordsPerMinute.toString(),
            ttsModelDraft = ai.ttsModel,
            maxCharsDraft = ai.maxTtsCharacters.toString(),
        )
    }

    fun setLiveTts(enabled: Boolean) {
        val updated = aiStore.get().copy(useLiveElevenLabs = enabled)
        aiStore.set(updated)
        _state.update { it.copy(ai = updated, ttsMessage = null) }
    }

    fun onTtsModel(v: String) = _state.update { it.copy(ttsModelDraft = v, ttsMessage = null) }
    fun onMaxChars(v: String) = _state.update { it.copy(maxCharsDraft = v.filter(Char::isDigit).take(6), ttsMessage = null) }

    fun saveTtsSettings() {
        val s = _state.value
        val model = s.ttsModelDraft.trim()
        val max = s.maxCharsDraft.toIntOrNull()
        val range = AiSettings.MAX_TTS_CHARACTERS_RANGE
        val error = when {
            model.isEmpty() -> "Enter a speech model name."
            max == null || max !in range -> "Character limit must be ${range.first} to ${range.last}."
            else -> null
        }
        if (error != null) {
            _state.update { it.copy(ttsMessage = error) }
            return
        }
        val updated = aiStore.get().copy(ttsModel = model, maxTtsCharacters = max!!)
        aiStore.set(updated)
        _state.update { it.copy(ai = updated, ttsMessage = "Saved.") }
    }

    fun setLive(enabled: Boolean) {
        val updated = aiStore.get().copy(useLiveOpenAi = enabled)
        aiStore.set(updated)
        _state.update { it.copy(ai = updated, aiMessage = null) }
    }

    fun onResearchModel(v: String) = _state.update { it.copy(researchModelDraft = v, aiMessage = null) }
    fun onScriptModel(v: String) = _state.update { it.copy(scriptModelDraft = v, aiMessage = null) }
    fun onWpm(v: String) = _state.update { it.copy(wpmDraft = v.filter(Char::isDigit).take(3), aiMessage = null) }

    fun saveAiSettings() {
        val s = _state.value
        val wpm = s.wpmDraft.toIntOrNull()
        val research = s.researchModelDraft.trim()
        val script = s.scriptModelDraft.trim()
        val error = when {
            research.isEmpty() || script.isEmpty() -> "Model names cannot be empty."
            wpm == null || wpm !in AiSettings.WPM_RANGE ->
                "Speaking rate must be ${AiSettings.WPM_RANGE.first} to ${AiSettings.WPM_RANGE.last} words per minute."
            else -> null
        }
        if (error != null) {
            _state.update { it.copy(aiMessage = error) }
            return
        }
        val updated = aiStore.get().copy(researchModel = research, scriptModel = script, wordsPerMinute = wpm!!)
        aiStore.set(updated)
        _state.update { it.copy(ai = updated, aiMessage = "Saved.") }
    }

    fun onInput(name: SecretKeyName, value: String) =
        _state.update { it.copy(input = it.input + (name to value)) }

    fun save(name: SecretKeyName) {
        val value = _state.value.input[name].orEmpty().trim()
        if (value.isEmpty()) return
        store.put(name, value)
        _state.update { it.copy(saved = snapshot(), input = it.input - name) }
    }

    fun clear(name: SecretKeyName) {
        store.clear(name)
        _state.update { it.copy(saved = snapshot(), input = it.input - name) }
    }
}

@Composable
fun SettingsScreen(viewModel: SettingsViewModel) {
    val s by viewModel.state.collectAsStateWithLifecycle()
    Column(
        Modifier
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(20.dp),
    ) {
        Text("Settings", style = MaterialTheme.typography.headlineSmall)
        Text(
            "API keys are encrypted with a key held in the Android Keystore, stay on this device, " +
                "and are excluded from backups. Stored keys are never shown.",
            style = MaterialTheme.typography.bodySmall,
        )
        KeyField("OpenAI API key", "openai", SecretKeyName.OPENAI, s, viewModel)
        KeyField("ElevenLabs API key", "elevenlabs", SecretKeyName.ELEVENLABS, s, viewModel)
        AiSection(s, viewModel)
        SpeechSection(s, viewModel)
    }
}

@Composable
private fun SpeechSection(s: SettingsUiState, vm: SettingsViewModel) {
    val noKey = s.saved[SecretKeyName.ELEVENLABS] != true
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("Speech generation", style = MaterialTheme.typography.titleMedium)
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("Use ElevenLabs for audio")
                Text(
                    "Turns the script into spoken audio and uses your ElevenLabs credits (about one per character). " +
                        "Off means the bundled sample audio is used.",
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            Switch(
                checked = s.ai.useLiveElevenLabs,
                onCheckedChange = vm::setLiveTts,
                modifier = Modifier.testTag("liveTtsSwitch"),
            )
        }
        if (s.ai.useLiveElevenLabs && noKey) {
            Text(
                "Live audio needs an ElevenLabs API key above.",
                color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.testTag("liveTtsNeedsKey"),
            )
        }
        OutlinedTextField(
            value = s.ttsModelDraft,
            onValueChange = vm::onTtsModel,
            label = { Text("Speech model") },
            supportingText = { Text(TtsModels.describe(s.ttsModelDraft), modifier = Modifier.testTag("ttsModelInfo")) },
            singleLine = true,
            modifier = Modifier.fillMaxWidth().testTag("ttsModel"),
        )
        OutlinedTextField(
            value = s.maxCharsDraft,
            onValueChange = vm::onMaxChars,
            label = { Text("Character limit per episode") },
            singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            modifier = Modifier.fillMaxWidth().testTag("maxChars"),
        )
        s.ttsMessage?.let {
            Text(it, style = MaterialTheme.typography.bodySmall, modifier = Modifier.testTag("ttsMessage"))
        }
        Button(onClick = vm::saveTtsSettings, modifier = Modifier.testTag("saveTts")) { Text("Save") }
    }
}

@Composable
private fun AiSection(s: SettingsUiState, vm: SettingsViewModel) {
    val noKey = s.saved[SecretKeyName.OPENAI] != true
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("Script generation", style = MaterialTheme.typography.titleMedium)
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("Use OpenAI for research and scripts")
                Text(
                    "Sends your topic to OpenAI (web search plus a script model) and uses your API credit. " +
                        "Off means offline simulated output.",
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            Switch(
                checked = s.ai.useLiveOpenAi,
                onCheckedChange = vm::setLive,
                modifier = Modifier.testTag("liveSwitch"),
            )
        }
        if (s.ai.useLiveOpenAi && noKey) {
            Text(
                "Live mode needs an OpenAI API key above.",
                color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.testTag("liveNeedsKey"),
            )
        }
        OutlinedTextField(
            value = s.researchModelDraft,
            onValueChange = vm::onResearchModel,
            label = { Text("Research model (must support web search)") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth().testTag("researchModel"),
        )
        OutlinedTextField(
            value = s.scriptModelDraft,
            onValueChange = vm::onScriptModel,
            label = { Text("Script model (must support structured output)") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth().testTag("scriptModel"),
        )
        OutlinedTextField(
            value = s.wpmDraft,
            onValueChange = vm::onWpm,
            label = { Text("Speaking rate (words per minute)") },
            singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            modifier = Modifier.fillMaxWidth().testTag("wpm"),
        )
        s.aiMessage?.let {
            Text(it, style = MaterialTheme.typography.bodySmall, modifier = Modifier.testTag("aiMessage"))
        }
        Button(onClick = vm::saveAiSettings, modifier = Modifier.testTag("saveAi")) { Text("Save") }
    }
}

@Composable
private fun KeyField(
    label: String,
    tag: String,
    name: SecretKeyName,
    s: SettingsUiState,
    vm: SettingsViewModel,
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        val isSaved = s.saved[name] == true
        Text(label, style = MaterialTheme.typography.titleSmall)
        Text(
            if (isSaved) "A key is stored (hidden)" else "No key stored",
            style = MaterialTheme.typography.bodySmall,
            modifier = Modifier.testTag("$tag-status"),
        )
        OutlinedTextField(
            value = s.input[name].orEmpty(),
            onValueChange = { vm.onInput(name, it) },
            label = { Text(if (isSaved) "Enter new key to replace" else "Enter key") },
            singleLine = true,
            visualTransformation = PasswordVisualTransformation(),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, autoCorrectEnabled = false),
            modifier = Modifier
                .fillMaxWidth()
                .testTag("$tag-field"),
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(
                onClick = { vm.save(name) },
                enabled = s.input[name].orEmpty().isNotBlank(),
                modifier = Modifier.testTag("$tag-save"),
            ) { Text("Save") }
            OutlinedButton(
                onClick = { vm.clear(name) },
                enabled = isSaved,
                modifier = Modifier.testTag("$tag-clear"),
            ) { Text("Remove") }
        }
    }
}
