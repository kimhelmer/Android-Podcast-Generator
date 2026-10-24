package com.podcaststudio.app

import android.Manifest
import android.app.Notification
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.media.AudioManager
import android.net.Uri
import android.os.Looper
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.test.core.app.ApplicationProvider
import com.podcaststudio.app.audio.PlaybackService
import com.podcaststudio.app.audio.WavFileWriter
import com.podcaststudio.app.domain.GenerationStatus
import com.podcaststudio.app.domain.Language
import com.podcaststudio.app.domain.PodcastEpisode
import com.podcaststudio.app.generation.GenerationService
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.io.File
import java.time.Duration

/**
 * Service lifecycles, run against the real application class and a real (in-sandbox) database.
 * These check wiring and rules; they do not prove behaviour on a physical phone.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = PodcastApp::class)
class ServicesTest {
    @get:Rule val tmp = TemporaryFolder()

    private val app: PodcastApp get() = ApplicationProvider.getApplicationContext()
    private val container: AppContainer get() = app.container
    private val looper get() = shadowOf(Looper.getMainLooper())

    private fun pump(untilTrue: () -> Boolean, timeoutMs: Long = 30_000, stepMs: Long = 50) {
        val end = System.currentTimeMillis() + timeoutMs
        while (!untilTrue()) {
            check(System.currentTimeMillis() < end) { "condition not met in $timeoutMs ms" }
            looper.idleFor(Duration.ofMillis(stepMs)) // advances virtual time so the simulated stages elapse
            Thread.sleep(5)
        }
    }

    // ---------------------------------------------------------------- generation foreground service

    @Test fun generationServiceGoesForegroundAtOnceThenStopsItselfWhenThereIsNothingToDo() {
        val controller = Robolectric.buildService(GenerationService::class.java).create()
        val service = controller.get()
        controller.startCommand(0, 1)

        // startForeground must happen immediately (Android kills the app after ~5 s). The data-sync type itself is
        // checked against the manifest in ProjectAuditTest. With nothing to do the service has already finished, so
        // check what it recorded rather than the live notification.
        assertEquals(1001, shadowOf(service).lastForegroundNotificationId)
        pump({ shadowOf(service).isStoppedBySelf })
        assertTrue(shadowOf(service).isForegroundStopped)
    }

    @Test fun generationServiceShowsStageProgressThenAResultAndStops() {
        shadowOf(app).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)
        assertTrue(container.coordinator.start(enRequest))

        val controller = Robolectric.buildService(GenerationService::class.java).create()
        val service = controller.get()
        controller.startCommand(0, 1)
        val nm = app.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

        // while running: an ongoing notification naming the stage
        pump({ shadowOf(nm).getNotification(1001)?.extras?.getString(Notification.EXTRA_TEXT)?.isNotBlank() == true })
        val ongoing = shadowOf(nm).getNotification(1001) ?: shadowOf(service).lastForegroundNotification
        assertTrue(ongoing.flags and Notification.FLAG_ONGOING_EVENT != 0)
        assertFalse(shadowOf(service).isStoppedBySelf)

        pump({ container.coordinator.job.value?.status == GenerationStatus.COMPLETED })
        pump({ shadowOf(service).isStoppedBySelf })

        val result = shadowOf(nm).getNotification(1002)
        assertNotNull("a result notification tells the user it finished", result)
        assertTrue(result.extras.getString(Notification.EXTRA_TEXT)!!.startsWith("Episode ready"))
        assertTrue(shadowOf(service).isForegroundStopped)
    }

    @Test fun aFailureIsReportedWithItsMessageNotJustAFinishedTone() {
        shadowOf(app).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)
        container.coordinator.start(enRequest.copy(topic = "")) // invalid: fails at once
        val controller = Robolectric.buildService(GenerationService::class.java).create()
        val service = controller.get()
        controller.startCommand(0, 1)
        val nm = app.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

        pump({ shadowOf(service).isStoppedBySelf })
        val text = shadowOf(nm).getNotification(1002)!!.extras.getString(Notification.EXTRA_TEXT)!!
        assertTrue(text, text.startsWith("Generation failed"))
    }

    @Test fun startingAJobLaunchesTheForegroundServiceAndAnIdleAppLaunchesNone() {
        looper.idle()
        assertNull("nothing runs while idle", shadowOf(app).nextStartedService)

        assertTrue(container.coordinator.start(enRequest))
        looper.idle()
        val intent = shadowOf(app).nextStartedService
        assertNotNull("generation must run inside a foreground service", intent)
        assertEquals(GenerationService::class.java.name, intent.component!!.className)
    }

    @Test fun aServiceStartedForAFinishedJobStopsRightAwayInsteadOfLingering() {
        container.coordinator.start(enRequest.copy(topic = "")) // a failed job is already on record
        val controller = Robolectric.buildService(GenerationService::class.java).create()
        val service = controller.get()
        controller.startCommand(0, 1)
        pump({ shadowOf(service).isStoppedBySelf })
        assertTrue(shadowOf(service).isForegroundStopped)
    }

    // ---------------------------------------------------------------- playback service

    private fun wavUri(seconds: Int = 3): Uri {
        val f = File(tmp.root, "t.wav")
        WavFileWriter(f, 24_000).use { it.write(pcmTone(seconds * 1000)) }
        return Uri.fromFile(f)
    }

    private fun playbackService() = Robolectric.buildService(PlaybackService::class.java).create().get()

    @Test fun aSeekWhilePausedIsSavedSoThePositionSurvivesLeavingTheApp() {
        runBlocking { container.episodes.save(PodcastEpisode("ep1", "T", "t", 1, Language.ENGLISH, 24, "file:///x.wav")) }
        val service = playbackService()
        val player = service.sessions.single().player
        player.setMediaItem(MediaItem.Builder().setMediaId("ep1").setUri("file:///x.wav").build())
        player.seekTo(9_000) // no play/pause event is involved

        pump({ runBlocking { container.episodes.getEpisode("ep1")!!.lastPositionMs } == 9_000L }, timeoutMs = 10_000)
        service.onDestroy()
    }

    @Test fun headphonesBeingUnpluggedPausePlayback() {
        val service = playbackService()
        val player = service.sessions.single().player
        player.setMediaItem(MediaItem.Builder().setMediaId("ep1").setUri(wavUri()).build())
        player.prepare()
        player.play()
        pump({ player.playbackState == Player.STATE_READY }, timeoutMs = 10_000)
        assertTrue(player.playWhenReady)

        app.sendBroadcast(Intent(AudioManager.ACTION_AUDIO_BECOMING_NOISY))
        looper.idle()

        assertFalse("unplugging headphones must not blast the speaker", player.playWhenReady)
        service.onDestroy()
    }

    @Test fun playbackRequestsAudioFocusAndYieldsAndResumesCorrectly() {
        val service = playbackService()
        val player = service.sessions.single().player
        val am = app.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        player.setMediaItem(MediaItem.Builder().setMediaId("ep1").setUri(wavUri()).build())
        player.prepare()
        player.play()
        pump({ player.playbackState == Player.STATE_READY }, timeoutMs = 10_000)

        val request = shadowOf(am).lastAudioFocusRequest
        assertNotNull("playing must request audio focus", request)
        val attrs = request.audioFocusRequest.audioAttributes
        assertEquals(android.media.AudioAttributes.USAGE_MEDIA, attrs.usage)
        assertEquals(android.media.AudioAttributes.CONTENT_TYPE_SPEECH, attrs.contentType)
        // the listener getter is a hidden framework API, so read it reflectively
        val listener = request.audioFocusRequest.javaClass.getMethod("getOnAudioFocusChangeListener")
            .invoke(request.audioFocusRequest) as AudioManager.OnAudioFocusChangeListener

        // a phone call: playback is suppressed, not abandoned, and comes back on its own afterwards
        listener.onAudioFocusChange(AudioManager.AUDIOFOCUS_LOSS_TRANSIENT)
        looper.idle()
        assertEquals(Player.PLAYBACK_SUPPRESSION_REASON_TRANSIENT_AUDIO_FOCUS_LOSS, player.playbackSuppressionReason)
        assertFalse("silent while the call is active", player.isPlaying)
        assertTrue("still wants to play, so it can resume", player.playWhenReady)
        listener.onAudioFocusChange(AudioManager.AUDIOFOCUS_GAIN) // call ended
        looper.idle()
        assertEquals(Player.PLAYBACK_SUPPRESSION_REASON_NONE, player.playbackSuppressionReason)
        assertTrue("resumes after a temporary interruption", player.playWhenReady)

        // another media app took over for good: stop, and do not fight it for focus afterwards
        listener.onAudioFocusChange(AudioManager.AUDIOFOCUS_LOSS)
        looper.idle()
        assertFalse(player.playWhenReady)
        listener.onAudioFocusChange(AudioManager.AUDIOFOCUS_GAIN)
        looper.idle()
        assertFalse("a permanent loss is not resumed automatically", player.playWhenReady)
        service.onDestroy()
    }

    @Test fun theSessionIsOwnedByTheServiceSoItOutlivesAnyScreen() {
        val service = playbackService()
        assertEquals(1, service.sessions.size)
        service.sessions.single().player.setMediaItem(MediaItem.Builder().setMediaId("ep1").setUri(wavUri()).build())
        assertEquals(1, service.sessions.single().player.mediaItemCount)
        service.onDestroy()
    }
}
