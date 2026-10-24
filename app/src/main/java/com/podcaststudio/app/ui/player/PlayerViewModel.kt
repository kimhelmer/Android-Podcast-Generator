package com.podcaststudio.app.ui.player

import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import com.podcaststudio.app.domain.EpisodeRepository
import com.podcaststudio.app.domain.PodcastEpisode
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class PlayerUiState(
    val episode: PodcastEpisode? = null,
    val notFound: Boolean = false,
    val connected: Boolean = false,
    val isPlaying: Boolean = false,
    val positionMs: Long = 0,
    val durationMs: Long = 0,
    val error: String? = null,
)

/**
 * UI side of playback. In the app, [connect] returns a MediaController for the player living in PlaybackService, so
 * this is only a remote control and leaving the screen (or locking the phone) does not stop the audio.
 * Tests pass a plain player instead.
 */
class PlayerViewModel(
    private val episodeId: String,
    private val repo: EpisodeRepository,
    private val connect: suspend () -> Player,
) : ViewModel() {
    private var controller: Player? = null
    private val _state = MutableStateFlow(PlayerUiState())
    val state: StateFlow<PlayerUiState> = _state.asStateFlow()

    private val listener = object : Player.Listener {
        override fun onPlayWhenReadyChanged(playWhenReady: Boolean, reason: Int) {
            controller?.let { c -> _state.update { it.copy(isPlaying = wantsToPlay(c)) } }
        }

        override fun onPlaybackStateChanged(playbackState: Int) {
            controller?.let { c -> _state.update { it.copy(isPlaying = wantsToPlay(c)) } }
        }

        override fun onPlayerError(error: PlaybackException) {
            _state.update { it.copy(error = "Playback failed (${error.errorCodeName})") }
        }
    }

    init {
        viewModelScope.launch {
            val ep = repo.getEpisode(episodeId)
            if (ep == null) {
                _state.update { it.copy(notFound = true) }
                return@launch
            }
            _state.update { it.copy(episode = ep, durationMs = ep.durationSeconds * 1000L) }
            val c = try {
                connect()
            } catch (e: Exception) {
                _state.update { it.copy(error = "Could not start the audio player.") }
                return@launch
            }
            controller = c
            c.addListener(listener)

            // Already playing this episode in the background? Just attach; otherwise load it, resuming where it stopped.
            if (c.currentMediaItem?.mediaId != ep.id) {
                val resumeAt = ep.lastPositionMs.takeIf { it > 0 && it < ep.durationSeconds * 1000L - 1000 } ?: 0L
                c.setMediaItem(mediaItemFor(ep), resumeAt)
                c.prepare()
            }
            _state.update { it.copy(connected = true, isPlaying = wantsToPlay(c)) }
            while (true) {
                publish(c)
                delay(250)
            }
        }
    }

    private fun mediaItemFor(ep: PodcastEpisode) = MediaItem.Builder()
        .setMediaId(ep.id)
        .setUri(Uri.parse(ep.audioUri))
        .setMediaMetadata(
            MediaMetadata.Builder()
                .setTitle(ep.title)
                .setArtist("Podcast Studio")
                .setIsPlayable(true)
                .build(),
        )
        .build()

    private fun publish(c: Player) {
        val d = c.duration
        _state.update {
            it.copy(
                isPlaying = wantsToPlay(c),
                positionMs = c.currentPosition.coerceAtLeast(0),
                durationMs = if (d != androidx.media3.common.C.TIME_UNSET && d > 0) d else it.durationMs,
            )
        }
    }

    fun togglePlay() {
        val c = controller ?: return
        if (wantsToPlay(c)) {
            c.pause()
        } else {
            if (c.playbackState == Player.STATE_ENDED) c.seekTo(0)
            c.play()
        }
    }

    /** True from the moment the user presses play, including while buffering, so a second tap pauses. */
    private fun wantsToPlay(c: Player) = c.playWhenReady && c.playbackState != Player.STATE_ENDED

    fun seekTo(ms: Long) {
        val c = controller ?: return
        c.seekTo(ms)
        publish(c)
    }

    override fun onCleared() {
        controller?.removeListener(listener)
        controller?.release() // for a MediaController this only disconnects; the service keeps playing
        controller = null
    }
}
