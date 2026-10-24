package com.podcaststudio.app

import android.app.Application
import android.content.Intent
import androidx.core.content.ContextCompat
import com.podcaststudio.app.generation.GenerationService
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

class PodcastApp : Application() {
    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        container = AppContainer.create(this)
        container.seedSampleEpisode()
        container.purgeOldDrafts()

        // Run generation inside a foreground service so it keeps going with the screen locked.
        container.appScope.launch {
            container.coordinator.job
                .map { it != null && !it.status.isTerminal }
                .distinctUntilChanged()
                .collect { active ->
                    if (active) {
                        runCatching { ContextCompat.startForegroundService(this@PodcastApp, Intent(this@PodcastApp, GenerationService::class.java)) }
                    }
                }
        }
    }
}
