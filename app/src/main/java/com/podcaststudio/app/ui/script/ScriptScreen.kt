package com.podcaststudio.app.ui.script

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.podcaststudio.app.domain.ContentRepository
import com.podcaststudio.app.domain.PodcastScript
import com.podcaststudio.app.domain.ResearchResult
import com.podcaststudio.app.domain.Speaker
import com.podcaststudio.app.ui.formatClock
import com.podcaststudio.app.ui.formatDate
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

data class ScriptUiState(
    val loading: Boolean = true,
    val script: PodcastScript? = null,
    val research: ResearchResult? = null,
    /** Characters actually sent to ElevenLabs for this episode (0 if speech was simulated). */
    val ttsCharacters: Int = 0,
)

class ScriptViewModel(episodeId: String, content: ContentRepository) : ViewModel() {
    private val _state = MutableStateFlow(ScriptUiState())
    val state: StateFlow<ScriptUiState> = _state.asStateFlow()

    init {
        viewModelScope.launch {
            _state.value = ScriptUiState(
                loading = false,
                script = content.getScript(episodeId),
                research = content.getResearch(episodeId),
                ttsCharacters = content.getTtsCharacters(episodeId),
            )
        }
    }
}

/** Actual usage only, as reported by the providers. No money amounts: prices depend on model and plan. */
fun usageSummary(script: PodcastScript, research: ResearchResult?, ttsCharacters: Int): List<String> = buildList {
    fun tokens(label: String, input: Int, output: Int) =
        if (input + output > 0) add("$label: $input tokens in, $output out") else add("$label: not reported")
    add("OpenAI usage (reported by OpenAI)")
    tokens("Research", research?.inputTokens ?: 0, research?.outputTokens ?: 0)
    tokens("Script", script.inputTokens, script.outputTokens)
    add(
        if (ttsCharacters > 0) "ElevenLabs: $ttsCharacters characters sent for speech (counted from accepted requests)"
        else "ElevenLabs: no speech characters sent",
    )
}

@Composable
fun ScriptScreen(viewModel: ScriptViewModel, onBack: () -> Unit) {
    val s by viewModel.state.collectAsStateWithLifecycle()
    val script = s.script
    val uriHandler = LocalUriHandler.current

    Column(Modifier.fillMaxSize()) {
        TextButton(onClick = onBack, modifier = Modifier.padding(horizontal = 8.dp)) { Text("Back") }
        when {
            s.loading -> Text("Loading...", modifier = Modifier.padding(16.dp))
            script == null -> Text(
                "No script is stored for this episode.",
                modifier = Modifier
                    .padding(16.dp)
                    .testTag("noScript"),
            )
            else -> LazyColumn(
                contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                item {
                    Text(script.title, style = MaterialTheme.typography.headlineSmall, modifier = Modifier.testTag("scriptTitle"))
                    Text(script.summary, style = MaterialTheme.typography.bodyMedium)
                    Text(
                        "${script.language.label} | ${script.wordCount} words | about ${formatClock(script.estimatedDurationSeconds * 1000L)}",
                        style = MaterialTheme.typography.bodySmall,
                    )
                    Text(
                        "Researched ${formatDate(script.researchedAt)} with ${script.researchModel}; script by ${script.scriptModel}",
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.testTag("scriptMeta"),
                    )
                    script.warnings.forEach {
                        Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                    }
                    Column(Modifier.testTag("usage").padding(top = 4.dp)) {
                        usageSummary(script, s.research, s.ttsCharacters).forEachIndexed { i, line ->
                            Text(
                                line,
                                style = if (i == 0) MaterialTheme.typography.labelMedium else MaterialTheme.typography.bodySmall,
                            )
                        }
                    }
                }
                var lastSection: String? = null
                script.segments.forEach { seg ->
                    val header = seg.section.takeIf { it.isNotBlank() && it != lastSection }
                    lastSection = seg.section
                    item(key = seg.id) {
                        Column {
                            if (header != null) {
                                Text(header, style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(top = 8.dp))
                            }
                            Text(
                                (if (seg.speaker == Speaker.HOST) "HOST" else "GUEST") +
                                    (seg.delivery?.let { " ($it)" } ?: ""),
                                style = MaterialTheme.typography.labelMedium,
                                fontWeight = FontWeight.Bold,
                            )
                            Text(seg.text, style = MaterialTheme.typography.bodyMedium)
                        }
                    }
                }
                item {
                    Text("Sources", style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(top = 12.dp))
                    if (script.sources.isEmpty()) Text("No sources.", style = MaterialTheme.typography.bodySmall)
                }
                items(script.sources, key = { it.url }) { src ->
                    Column(Modifier.clickable { uriHandler.openUri(src.url) }) {
                        Text(src.title, style = MaterialTheme.typography.bodyMedium, textDecoration = TextDecoration.Underline)
                        Text(src.url, style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
        }
    }
}
