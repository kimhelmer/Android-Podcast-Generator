package com.podcaststudio.app

import android.content.Context
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.test.core.app.ApplicationProvider
import com.podcaststudio.app.audio.PlaybackService
import com.podcaststudio.app.domain.Language
import com.podcaststudio.app.domain.PodcastEpisode
import com.podcaststudio.app.ui.player.PlayerViewModel
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.time.Duration

/**
 * What can be checked without a device: how the player is configured for background use, and the
 * resume logic. Whether audio is actually audible with the screen locked still needs a real phone.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class PlaybackTest {
    private val ctx: Context = ApplicationProvider.getApplicationContext()
    private lateinit var player: ExoPlayer
    private val store = ViewModelStore()
    private val repo = FakeRepo()

    @Before fun setUp() {
        player = ExoPlayer.Builder(ctx).build()
    }

    @After fun tearDown() {
        store.clear()
        player.release()
    }

    private fun episode(position: Long = 0, durationSeconds: Int = 24) = PodcastEpisode(
        "ep1", "Bees", "topic", 1, Language.ENGLISH, durationSeconds, "file:///episode.wav", position,
    )

    private fun viewModel(): PlayerViewModel {
        val factory = viewModelFactory { initializer { PlayerViewModel("ep1", repo, { player }) } }
        return ViewModelProvider(store, factory)[PlayerViewModel::class.java]
    }

    private fun settle() = shadowOf(android.os.Looper.getMainLooper()).idleFor(Duration.ofMillis(600))

    // ---- resume logic ----

    @Test fun loadsEpisodeAndResumesWhereItStopped() {
        runBlocking { repo.save(episode(position = 7_000)) }
        val vm = viewModel()
        settle()

        assertEquals("ep1", player.currentMediaItem?.mediaId)
        assertEquals("Bees", player.currentMediaItem?.mediaMetadata?.title)
        assertEquals(7_000L, player.currentPosition)
        assertTrue(vm.state.value.connected)
        assertEquals("Bees", vm.state.value.episode?.title)
    }

    @Test fun anEpisodeLeftAtTheVeryEndRestartsFromTheBeginning() {
        runBlocking { repo.save(episode(position = 23_800, durationSeconds = 24)) }
        viewModel()
        settle()
        assertEquals(0L, player.currentPosition)
    }

    @Test fun attachingToAnAlreadyPlayingEpisodeDoesNotRestartIt() {
        runBlocking { repo.save(episode(position = 2_000)) }
        // background service is already playing this episode, further along than the saved position
        player.setMediaItem(MediaItem.Builder().setMediaId("ep1").setUri("file:///episode.wav").build(), 15_000)
        player.prepare()
        viewModel()
        settle()
        assertEquals(15_000L, player.currentPosition)
    }

    @Test fun switchingEpisodeLoadsTheNewOne() {
        runBlocking { repo.save(episode(position = 1_000)) }
        player.setMediaItem(MediaItem.Builder().setMediaId("other").setUri("file:///other.wav").build())
        viewModel()
        settle()
        assertEquals("ep1", player.currentMediaItem?.mediaId)
    }

    @Test fun missingEpisodeIsReported() {
        val vm = viewModel()
        settle()
        assertTrue(vm.state.value.notFound)
        assertFalse(vm.state.value.connected)
    }

    @Test fun controlsReachThePlayer() {
        runBlocking { repo.save(episode()) }
        val vm = viewModel()
        settle()
        vm.seekTo(9_000)
        assertEquals(9_000L, player.currentPosition)
        vm.togglePlay()
        assertTrue(player.playWhenReady)
        vm.togglePlay()
        assertFalse(player.playWhenReady)
    }

    @Test fun leavingTheScreenDoesNotStopThePlayer() {
        runBlocking { repo.save(episode()) }
        val vm = viewModel()
        settle()
        vm.togglePlay()
        assertTrue(player.playWhenReady)
        store.clear() // screen closed: view model cleared
        // with a MediaController this only disconnects; the playing session is untouched
        assertTrue(player.mediaItemCount == 1)
    }

    @Test fun connectionFailureShowsAnErrorInsteadOfCrashing() {
        runBlocking { repo.save(episode()) }
        val factory = viewModelFactory { initializer { PlayerViewModel("ep1", repo, { error("service unavailable") }) } }
        val vm = ViewModelProvider(store, factory)[PlayerViewModel::class.java]
        settle()
        assertEquals("Could not start the audio player.", vm.state.value.error)
        assertFalse(vm.state.value.connected)
    }

    // ---- the background service ----

    @Test fun serviceBuildsASpeechSessionWithAudioFocusHandling() {
        val service = Robolectric.buildService(PlaybackService::class.java).create().get()
        try {
            assertEquals(1, service.sessions.size)
            val p: Player = service.sessions[0].player
            assertEquals(C.USAGE_MEDIA, p.audioAttributes.usage)
            assertEquals(C.AUDIO_CONTENT_TYPE_SPEECH, p.audioAttributes.contentType)
            assertFalse(p.playWhenReady) // never autoplays
            assertNotNull(service.sessions[0].token)
        } finally {
            service.onDestroy()
        }
    }
}
