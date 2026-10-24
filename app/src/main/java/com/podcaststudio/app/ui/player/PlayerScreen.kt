package com.podcaststudio.app.ui.player

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.podcaststudio.app.ui.formatClock

@Composable
fun PlayerScreen(viewModel: PlayerViewModel, onBack: () -> Unit, onOpenScript: () -> Unit) {
    val s by viewModel.state.collectAsStateWithLifecycle()

    Column(
        Modifier
            .fillMaxSize()
            .padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp, Alignment.CenterVertically),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        if (s.notFound) {
            Text("Episode not found.")
            TextButton(onClick = onBack) { Text("Back") }
            return@Column
        }
        Text(
            s.episode?.title ?: "Loading...",
            style = MaterialTheme.typography.headlineSmall,
            modifier = Modifier.testTag("playerTitle"),
        )

        var dragging by remember { mutableStateOf<Float?>(null) }
        val duration = s.durationMs.coerceAtLeast(1)
        Slider(
            value = dragging ?: s.positionMs.coerceIn(0, duration).toFloat(),
            onValueChange = { dragging = it },
            onValueChangeFinished = {
                dragging?.let { viewModel.seekTo(it.toLong()) }
                dragging = null
            },
            valueRange = 0f..duration.toFloat(),
            modifier = Modifier
                .fillMaxWidth()
                .testTag("seekBar"),
        )
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(formatClock((dragging ?: s.positionMs.toFloat()).toLong()), modifier = Modifier.testTag("elapsed"))
            Text(formatClock(s.durationMs), modifier = Modifier.testTag("total"))
        }
        FilledIconButton(
            onClick = viewModel::togglePlay,
            enabled = s.connected,
            modifier = Modifier.testTag("playPause"),
        ) {
            Icon(
                if (s.isPlaying) Icons.Filled.Pause else Icons.Filled.PlayArrow,
                contentDescription = if (s.isPlaying) "Pause" else "Play",
            )
        }
        Text(
            "Keeps playing when the screen is off or the app is in the background.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        TextButton(onClick = onOpenScript, modifier = Modifier.testTag("viewScript")) { Text("View script and sources") }
        s.error?.let { Text(it, color = MaterialTheme.colorScheme.error, modifier = Modifier.testTag("playerError")) }
    }
}
