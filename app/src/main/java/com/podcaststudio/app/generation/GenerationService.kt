package com.podcaststudio.app.generation

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import com.podcaststudio.app.MainActivity
import com.podcaststudio.app.PodcastApp
import com.podcaststudio.app.domain.GenerationJob
import com.podcaststudio.app.domain.GenerationStatus
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * Keeps the process alive and in the foreground while an episode is being generated, so a run that takes
 * minutes is not killed or throttled when the screen locks. The work itself still runs in the coordinator.
 */
class GenerationService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var watching = false

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val container = (application as? PodcastApp)?.container
        ensureChannels()
        ServiceCompat.startForeground(
            this, ONGOING_ID, ongoing("Preparing your episode..."),
            if (Build.VERSION.SDK_INT >= 29) ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC else 0,
        )
        if (container == null) {
            stopSelf()
            return START_NOT_STICKY
        }
        if (!watching) {
            watching = true
            scope.launch {
                container.coordinator.job.collect { job ->
                    when {
                        job == null -> finish(null, container)
                        job.status.isTerminal -> finish(job, container)
                        else -> notify(ONGOING_ID, ongoing(job.stageLabel))
                    }
                }
            }
        }
        return START_NOT_STICKY // a killed process loses the in-memory job anyway
    }

    private suspend fun finish(job: GenerationJob?, container: com.podcaststudio.app.AppContainer) {
        // A new job may already have started (Retry audio right after a failure); never stop under a running job.
        val current = container.coordinator.job.value
        if (current != null && !current.status.isTerminal) return
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        if (job != null) {
            val text = when (job.status) {
                GenerationStatus.COMPLETED -> {
                    val title = job.episodeId?.let { container.episodes.getEpisode(it)?.title }
                    "Episode ready" + (title?.let { ": $it" } ?: "")
                }
                GenerationStatus.FAILED -> "Generation failed: ${job.error ?: "unknown error"}"
                else -> null
            }
            if (text != null) notify(RESULT_ID, result(text))
        }
        stopSelf()
    }

    private fun notify(id: Int, n: Notification) {
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            return // the foreground notification still shows in the task manager
        }
        getSystemService(NotificationManager::class.java).notify(id, n)
    }

    private fun open(): PendingIntent = PendingIntent.getActivity(
        this, 0, Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

    private fun ongoing(text: String): Notification = NotificationCompat.Builder(this, CHANNEL_PROGRESS)
        .setSmallIcon(android.R.drawable.stat_sys_download)
        .setContentTitle("Podcast Studio")
        .setContentText(text)
        .setOngoing(true)
        .setOnlyAlertOnce(true)
        .setContentIntent(open())
        .build()

    private fun result(text: String): Notification = NotificationCompat.Builder(this, CHANNEL_RESULT)
        .setSmallIcon(android.R.drawable.stat_sys_download_done)
        .setContentTitle("Podcast Studio")
        .setContentText(text)
        .setStyle(NotificationCompat.BigTextStyle().bigText(text))
        .setAutoCancel(true)
        .setContentIntent(open())
        .build()

    private fun ensureChannels() {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_PROGRESS, "Episode generation", NotificationManager.IMPORTANCE_LOW),
        )
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_RESULT, "Episode results", NotificationManager.IMPORTANCE_DEFAULT),
        )
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    private companion object {
        const val CHANNEL_PROGRESS = "generation_progress"
        const val CHANNEL_RESULT = "generation_result"
        const val ONGOING_ID = 1001
        const val RESULT_ID = 1002
    }
}
