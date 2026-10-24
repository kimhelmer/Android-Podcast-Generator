package com.podcaststudio.app.audio

import android.os.Handler
import android.os.Looper
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import com.podcaststudio.app.PodcastApp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * Owns the one ExoPlayer and its MediaSession, so playback continues with the screen off or the app in the
 * background, with lock-screen and notification controls. The UI only holds a MediaController.
 */
class PlaybackService : MediaSessionService() {
    private var session: MediaSession? = null
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val handler = Handler(Looper.getMainLooper())
    private val saveTick = object : Runnable {
        override fun run() {
            savePosition()
            handler.postDelayed(this, SAVE_INTERVAL_MS)
        }
    }

    @UnstableApi // builds OwnAppOnly, which uses session calls still marked unstable in media3 1.4
    override fun onCreate() {
        super.onCreate()
        val player = ExoPlayer.Builder(this)
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(C.USAGE_MEDIA)
                    .setContentType(C.AUDIO_CONTENT_TYPE_SPEECH)
                    .build(),
                /* handleAudioFocus = */ true,
            )
            .setHandleAudioBecomingNoisy(true) // pause when headphones are unplugged
            .setWakeMode(C.WAKE_MODE_LOCAL) // keep the CPU awake while a local file plays with the screen off
            .build()
        player.addListener(object : Player.Listener {
            override fun onIsPlayingChanged(isPlaying: Boolean) {
                handler.removeCallbacks(saveTick)
                if (isPlaying) handler.postDelayed(saveTick, SAVE_INTERVAL_MS) else savePosition()
            }

            override fun onPlaybackStateChanged(playbackState: Int) {
                if (playbackState == Player.STATE_ENDED) savePosition()
            }

            override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) = savePosition()

            // A seek while paused changes the position without any play/pause event, so save it explicitly.
            override fun onPositionDiscontinuity(oldPosition: Player.PositionInfo, newPosition: Player.PositionInfo, reason: Int) {
                if (reason == Player.DISCONTINUITY_REASON_SEEK) savePosition()
            }
        })
        val created = MediaSession.Builder(this, player).setCallback(OwnAppOnly()).build()
        session = created
        // Register now so notification and lock-screen controls work even if playback starts before a controller connects.
        addSession(created)
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? = session

    override fun onDestroy() {
        handler.removeCallbacks(saveTick)
        savePosition()
        session?.run {
            player.release()
            release()
        }
        session = null
        scope.cancel()
        super.onDestroy()
    }

    /** Remembers where the listener stopped; a finished episode restarts from the beginning. */
    private fun savePosition() {
        val player = session?.player ?: return
        val id = player.currentMediaItem?.mediaId?.takeIf { it.isNotEmpty() } ?: return
        val position = if (player.playbackState == Player.STATE_ENDED) 0L else player.currentPosition
        val repo = (application as? PodcastApp)?.container?.episodes ?: return
        scope.launch { repo.updatePosition(id, position) }
    }

    /** Only this app, the system media UI and trusted system controllers may drive playback. */
    @UnstableApi // isTrusted / isMediaNotificationController
    private inner class OwnAppOnly : MediaSession.Callback {
        override fun onConnect(session: MediaSession, controller: MediaSession.ControllerInfo): MediaSession.ConnectionResult =
            if (controller.packageName == packageName || session.isMediaNotificationController(controller) || controller.isTrusted) {
                MediaSession.ConnectionResult.AcceptedResultBuilder(session).build()
            } else {
                MediaSession.ConnectionResult.reject()
            }
    }

    private companion object {
        const val SAVE_INTERVAL_MS = 5_000L
    }
}
