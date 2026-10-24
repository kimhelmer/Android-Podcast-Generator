package com.podcaststudio.app.audio

import android.content.ComponentName
import android.content.Context
import androidx.media3.common.Player
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import kotlinx.coroutines.guava.await

/** Connects to the player owned by [PlaybackService]. The returned controller is a remote control, not a player. */
suspend fun connectToPlaybackService(context: Context): Player {
    val app = context.applicationContext
    val token = SessionToken(app, ComponentName(app, PlaybackService::class.java))
    return MediaController.Builder(app, token).buildAsync().await()
}
