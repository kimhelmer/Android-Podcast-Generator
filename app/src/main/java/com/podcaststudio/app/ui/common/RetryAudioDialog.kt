package com.podcaststudio.app.ui.common

import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import com.podcaststudio.app.domain.SpeechPlan

/** Asks before resuming audio generation, stating exactly what will and will not be requested. */
@Composable
fun RetryAudioDialog(plan: SpeechPlan, onConfirm: () -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        modifier = Modifier.testTag("retryDialog"),
        title = { Text("Retry audio?") },
        text = {
            Text(
                if (plan.remainingSegments == 0) {
                    "All ${plan.totalSegments} segments are already generated. Nothing new will be requested; " +
                        "the episode will just be assembled."
                } else {
                    "${plan.reusableSegments} of ${plan.totalSegments} segments are already done and will be reused " +
                        "(no charge). ${plan.remainingSegments} will be requested from ElevenLabs: " +
                        "${plan.charactersToSend} characters. OpenAI research and the script are not repeated."
                },
            )
        },
        confirmButton = { TextButton(onClick = onConfirm, modifier = Modifier.testTag("confirmRetry")) { Text("Retry audio") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}
