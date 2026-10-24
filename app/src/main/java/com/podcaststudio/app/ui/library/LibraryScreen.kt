package com.podcaststudio.app.ui.library

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.layout.Row
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.lifecycle.viewModelScope
import com.podcaststudio.app.AppContainer
import kotlinx.coroutines.launch
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.podcaststudio.app.domain.EpisodeRepository
import com.podcaststudio.app.domain.PodcastEpisode
import com.podcaststudio.app.ui.formatClock
import com.podcaststudio.app.ui.formatDate

class LibraryViewModel(
    episodes: EpisodeRepository,
    private val remove: suspend (PodcastEpisode) -> Unit,
) : ViewModel() {
    val episodes = episodes.observeEpisodes()

    fun delete(episode: PodcastEpisode) {
        viewModelScope.launch { remove(episode) }
    }
}

@Composable
fun LibraryScreen(viewModel: LibraryViewModel, onPlay: (String) -> Unit) {
    val list by viewModel.episodes.collectAsStateWithLifecycle(initialValue = emptyList())
    var pendingDelete by remember { mutableStateOf<PodcastEpisode?>(null) }

    pendingDelete?.let { ep ->
        AlertDialog(
            onDismissRequest = { pendingDelete = null },
            title = { Text("Delete episode?") },
            text = { Text("\"${ep.title}\" and its audio, script and sources will be removed from this phone.") },
            confirmButton = {
                TextButton(
                    onClick = { viewModel.delete(ep); pendingDelete = null },
                    modifier = Modifier.testTag("confirmDelete"),
                ) { Text("Delete") }
            },
            dismissButton = { TextButton(onClick = { pendingDelete = null }) { Text("Cancel") } },
        )
    }

    Column(Modifier.fillMaxSize()) {
        Text(
            "Podcast Library",
            style = MaterialTheme.typography.headlineSmall,
            modifier = Modifier.padding(16.dp),
        )
        if (list.isEmpty()) {
            Text("No episodes yet.", modifier = Modifier.padding(16.dp))
        }
        LazyColumn(
            contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 16.dp, vertical = 4.dp),
            verticalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(8.dp),
        ) {
            items(list, key = { it.id }) { ep ->
                EpisodeRow(ep, onClick = { onPlay(ep.id) }, onDelete = { pendingDelete = ep })
            }
        }
    }
}

@Composable
private fun EpisodeRow(ep: PodcastEpisode, onClick: () -> Unit, onDelete: () -> Unit) {
    Card(
        Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .testTag("episode-${ep.id}"),
    ) {
        Row(Modifier.padding(start = 16.dp, top = 8.dp, bottom = 8.dp, end = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(ep.title, style = MaterialTheme.typography.titleMedium)
                Text(
                    "${formatDate(ep.createdAt)}  |  ${ep.language.label}  |  ${formatClock(ep.durationSeconds * 1000L)}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (ep.id != AppContainer.SAMPLE_EPISODE_ID && ep.audioUri.startsWith("android.resource:")) {
                    // simulated speech: say so rather than let it pass as a real recording
                    Text(
                        "Placeholder audio (ElevenLabs was off)",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.tertiary,
                        modifier = Modifier.testTag("placeholder-${ep.id}"),
                    )
                }
            }
            if (ep.id != AppContainer.SAMPLE_EPISODE_ID) {
                IconButton(onClick = onDelete, modifier = Modifier.testTag("delete-${ep.id}")) {
                    Icon(Icons.Filled.Delete, contentDescription = "Delete ${ep.title}")
                }
            }
        }
    }
}
