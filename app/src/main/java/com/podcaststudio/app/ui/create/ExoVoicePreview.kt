package com.podcaststudio.app.ui.create

import android.content.Context
import android.net.Uri
import androidx.media3.common.C
import androidx.media3.common.AudioAttributes
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer

/** Streams a voice's sample clip over HTTPS with its own short-lived player, separate from episode playback. */
class ExoVoicePreview(private val context: Context) : VoicePreview {
    override var onFinished: (() -> Unit)? = null
    private var player: ExoPlayer? = null

    override fun play(url: String) {
        if (!url.startsWith("https://")) return
        val p = player ?: ExoPlayer.Builder(context.applicationContext).build().also {
            it.setAudioAttributes(
                AudioAttributes.Builder().setUsage(C.USAGE_MEDIA).setContentType(C.AUDIO_CONTENT_TYPE_SPEECH).build(),
                true,
            )
            it.addListener(object : Player.Listener {
                override fun onPlaybackStateChanged(state: Int) {
                    if (state == Player.STATE_ENDED) finish()
                }

                override fun onPlayerError(error: androidx.media3.common.PlaybackException) = finish()
            })
            player = it
        }
        p.setMediaItem(MediaItem.fromUri(Uri.parse(url)))
        p.prepare()
        p.play()
    }

    override fun stop() {
        player?.stop() // also gives audio focus back, so an episode that was playing can resume
    }

    private fun finish() {
        player?.stop() // a finished clip still holds audio focus until stopped
        onFinished?.invoke()
    }

    override fun release() {
        player?.release()
        player = null
    }
}
