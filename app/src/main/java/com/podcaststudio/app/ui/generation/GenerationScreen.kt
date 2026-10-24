package com.podcaststudio.app.ui.generation

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.podcaststudio.app.domain.GenerationCoordinator
import com.podcaststudio.app.domain.GenerationJob
import com.podcaststudio.app.domain.GenerationStatus
import com.podcaststudio.app.domain.SpeechPlan
import com.podcaststudio.app.ui.common.RetryAudioDialog
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

class GenerationViewModel(private val coordinator: GenerationCoordinator) : ViewModel() {
    val job: StateFlow<GenerationJob?> = coordinator.job

    private val _retryPlan = MutableStateFlow<SpeechPlan?>(null)

    /** Set while the user is being asked to approve a retry. */
    val retryPlan: StateFlow<SpeechPlan?> = _retryPlan.asStateFlow()
    private val _notice = MutableStateFlow<String?>(null)
    val notice: StateFlow<String?> = _notice.asStateFlow()

    fun cancel() = coordinator.cancel()
    fun dismiss() = coordinator.reset()

    /** Works out exactly what a retry would send, then asks for approval. Nothing is requested yet. */
    fun askRetry() {
        val id = job.value?.id ?: return
        viewModelScope.launch {
            val plan = coordinator.planRetry(id)
            if (plan == null) _notice.value = "This episode can no longer be resumed." else _retryPlan.value = plan
        }
    }

    fun dismissRetry() { _retryPlan.value = null }

    /** Runs in the view model's main-thread scope, which the coordinator's one-run-at-a-time bookkeeping relies on. */
    fun confirmRetry() {
        val id = job.value?.id ?: return
        _retryPlan.update { null }
        viewModelScope.launch {
            if (!coordinator.retryAudio(id)) _notice.value = "Could not resume: another episode is running, or the draft is gone."
        }
    }
}

@Composable
fun GenerationScreen(
    viewModel: GenerationViewModel,
    onOpenEpisode: (String) -> Unit,
    onDone: () -> Unit,
) {
    val job by viewModel.job.collectAsStateWithLifecycle()
    val plan by viewModel.retryPlan.collectAsStateWithLifecycle()
    val notice by viewModel.notice.collectAsStateWithLifecycle()

    Column(
        Modifier
            .fillMaxSize()
            .padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp, Alignment.CenterVertically),
    ) {
        val current = job
        if (current == null) {
            Text("No generation in progress.")
            notice?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            Button(onClick = onDone) { Text("Back") }
            return@Column
        }

        Text("Generating episode", style = MaterialTheme.typography.headlineSmall)
        Text(current.request.topic.trim(), maxLines = 3, style = MaterialTheme.typography.bodyMedium)
        Text(
            current.stageLabel,
            style = MaterialTheme.typography.titleLarge,
            modifier = Modifier.testTag("stageLabel"),
        )
        LinearProgressIndicator(progress = { current.progress }, modifier = Modifier.fillMaxWidth())
        notice?.let { Text(it, color = MaterialTheme.colorScheme.error, modifier = Modifier.testTag("notice")) }

        when (current.status) {
            GenerationStatus.COMPLETED -> {
                Text("Episode saved to your library.")
                Button(
                    onClick = { current.episodeId?.let(onOpenEpisode) },
                    modifier = Modifier.testTag("playEpisodeButton"),
                ) { Text("Play episode") }
                OutlinedButton(onClick = { viewModel.dismiss(); onDone() }) { Text("Done") }
            }
            GenerationStatus.FAILED, GenerationStatus.CANCELLED -> {
                if (current.status == GenerationStatus.FAILED) {
                    Text(
                        current.error ?: "Generation failed.",
                        color = MaterialTheme.colorScheme.error,
                        modifier = Modifier.testTag("errorText"),
                    )
                } else {
                    Text("Generation was cancelled.")
                }
                if (current.hasScript) {
                    Text(
                        "Your script is saved" + (if (current.audioDone > 0) " and ${current.audioDone} audio segments are done" else "") +
                            ". Retry audio continues from where it stopped without repeating research or the script.",
                        style = MaterialTheme.typography.bodySmall,
                    )
                    Button(onClick = viewModel::askRetry, modifier = Modifier.testTag("retryAudioButton")) { Text("Retry audio") }
                }
                OutlinedButton(onClick = { viewModel.dismiss(); onDone() }) { Text("Back") }
            }
            else -> OutlinedButton(
                onClick = viewModel::cancel,
                modifier = Modifier.testTag("cancelButton"),
            ) { Text("Cancel") }
        }
    }

    plan?.let { p ->
        RetryAudioDialog(
            plan = p,
            onConfirm = viewModel::confirmRetry,
            onDismiss = viewModel::dismissRetry,
        )
    }
}
