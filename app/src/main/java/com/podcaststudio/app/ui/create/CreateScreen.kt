package com.podcaststudio.app.ui.create

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.TextButton
import com.podcaststudio.app.ui.common.RetryAudioDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.podcaststudio.app.domain.Language
import com.podcaststudio.app.domain.PodcastRequest
import com.podcaststudio.app.domain.ValidationError
import com.podcaststudio.app.domain.VoiceProfile
import com.podcaststudio.app.ui.message

@Composable
fun CreateScreen(viewModel: CreateViewModel, onStarted: () -> Unit) {
    val s by viewModel.state.collectAsStateWithLifecycle()
    LifecycleResumeEffect(Unit) {
        viewModel.refreshMode()
        onPauseOrDispose { viewModel.stopPreview() } // a sample must not keep playing (or holding audio focus) elsewhere
    }

    Column(
        Modifier
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text("Create Podcast", style = MaterialTheme.typography.headlineSmall)

        s.draft?.let { draft ->
            Card(Modifier.fillMaxWidth().testTag("draftBanner")) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Unfinished episode", style = MaterialTheme.typography.titleSmall)
                    Text(
                        "\"${draft.title}\" has its script, but the audio did not finish. Retry audio reuses everything " +
                            "already generated and only requests what is missing.",
                        style = MaterialTheme.typography.bodySmall,
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(onClick = viewModel::askRetry, modifier = Modifier.testTag("retryDraft")) { Text("Retry audio") }
                        OutlinedButton(onClick = viewModel::discardDraft, modifier = Modifier.testTag("discardDraft")) { Text("Discard") }
                    }
                }
            }
        }
        s.message?.let { Text(it, color = MaterialTheme.colorScheme.error, modifier = Modifier.testTag("createMessage")) }

        val topicErrors = s.errors.filter {
            it == ValidationError.TOPIC_EMPTY || it == ValidationError.TOPIC_TOO_SHORT || it == ValidationError.TOPIC_TOO_LONG
        }
        OutlinedTextField(
            value = s.topic,
            onValueChange = viewModel::setTopic,
            label = { Text("Topic description") },
            minLines = 4,
            maxLines = 8,
            isError = topicErrors.isNotEmpty(),
            supportingText = { topicErrors.firstOrNull()?.let { Text(it.message()) } },
            modifier = Modifier
                .fillMaxWidth()
                .testTag("topicField"),
        )

        Text("Language", style = MaterialTheme.typography.titleSmall)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Language.entries.forEach { lang ->
                FilterChip(
                    selected = s.language == lang,
                    onClick = { viewModel.setLanguage(lang) },
                    label = { Text(lang.label) },
                )
            }
        }

        Text("Duration", style = MaterialTheme.typography.titleSmall)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            PodcastRequest.ALLOWED_DURATIONS.forEach { minutes ->
                FilterChip(
                    selected = s.durationMinutes == minutes,
                    onClick = { viewModel.setDuration(minutes) },
                    label = { Text("$minutes min") },
                )
            }
        }

        if (s.loadingVoices) Text("Loading voices...", style = MaterialTheme.typography.bodySmall)
        s.voiceError?.let {
            Text(
                it,
                color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.testTag("voiceError"),
            )
        }
        VoiceSelector(
            label = "Host voice",
            tag = "hostVoice",
            selected = s.host,
            options = s.voicesForLanguage,
            previewingUrl = s.previewingUrl,
            onSelect = viewModel::setHost,
            onPreview = viewModel::togglePreview,
        )
        VoiceSelector(
            label = "Guest voice",
            tag = "guestVoice",
            selected = s.guest,
            options = s.voicesForLanguage,
            previewingUrl = s.previewingUrl,
            onSelect = viewModel::setGuest,
            onPreview = viewModel::togglePreview,
        )

        val otherErrors = s.errors.filter { it !in topicErrors }
        otherErrors.forEach {
            Text(it.message(), color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
        }

        Button(
            onClick = { if (viewModel.generate() == GenerateResult.STARTED) onStarted() },
            modifier = Modifier
                .fillMaxWidth()
                .testTag("generateButton"),
        ) { Text("Generate") }
        Text(
            if (!s.liveOpenAi && !s.liveAudio) {
                "Simulated mode: nothing is sent to any AI service. Turn on OpenAI and ElevenLabs in Settings for a real episode."
            } else {
                (if (s.liveOpenAi) "Your topic is sent to OpenAI and uses your API credit. " else "The script is simulated. ") +
                    (if (s.liveAudio) "Speech is estimated at about ${s.estimatedChars} ElevenLabs characters (exact once the script is written)." else "Audio is the bundled sample.")
            },
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.testTag("modeNote"),
        )
    }

    s.approval?.let { a ->
        AlertDialog(
            onDismissRequest = viewModel::dismissApproval,
            modifier = Modifier.testTag("approvalDialog"),
            title = { Text("Start paid generation?") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (a.liveOpenAi) {
                        Text(
                            "OpenAI: web research plus a script, billed by tokens. If the script fails its checks it is " +
                                "rewritten once, which is one more call. Actual token usage is shown on the script page afterwards.",
                        )
                    }
                    if (a.liveAudio) {
                        Text(
                            "ElevenLabs: about ${a.estimatedChars} characters of speech (an estimate; the exact count is known " +
                                "once the script is written and is checked against your limit of ${a.maxChars}).",
                        )
                    }
                    Text("Nothing is retried automatically if a step fails. You decide whether to try again.")
                }
            },
            confirmButton = {
                TextButton(
                    onClick = { if (viewModel.approveAndStart() == GenerateResult.STARTED) onStarted() },
                    modifier = Modifier.testTag("approveStart"),
                ) { Text("Start") }
            },
            dismissButton = {
                TextButton(onClick = viewModel::dismissApproval, modifier = Modifier.testTag("declineStart")) { Text("Cancel") }
            },
        )
    }

    s.retryApproval?.let { r ->
        RetryAudioDialog(
            plan = r.plan,
            onConfirm = { viewModel.confirmRetry(onStarted) },
            onDismiss = viewModel::dismissRetry,
        )
    }
}

@Composable
private fun VoiceSelector(
    label: String,
    tag: String,
    selected: VoiceProfile?,
    options: List<VoiceProfile>,
    previewingUrl: String?,
    onSelect: (VoiceProfile) -> Unit,
    onPreview: (VoiceProfile) -> Unit,
) {
    var open by remember { mutableStateOf(false) }
    Column {
        Text(label, style = MaterialTheme.typography.titleSmall)
        OutlinedButton(
            onClick = { open = true },
            modifier = Modifier
                .fillMaxWidth()
                .testTag(tag),
        ) {
            Text(selected?.let { if (it.description.isBlank()) it.name else "${it.name} - ${it.description}" } ?: "Select voice")
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            options.forEach { v ->
                DropdownMenuItem(
                    text = { Text(if (v.description.isBlank()) v.name else "${v.name} - ${v.description}") },
                    onClick = {
                        onSelect(v)
                        open = false
                    },
                    trailingIcon = v.previewUrl?.let { url ->
                        {
                            val playing = previewingUrl == url
                            IconButton(onClick = { onPreview(v) }, modifier = Modifier.testTag("preview-${v.id}")) {
                                Icon(
                                    if (playing) Icons.Filled.Stop else Icons.Filled.PlayArrow,
                                    contentDescription = if (playing) "Stop preview of ${v.name}" else "Preview ${v.name}",
                                )
                            }
                        }
                    },
                )
            }
        }
    }
}
