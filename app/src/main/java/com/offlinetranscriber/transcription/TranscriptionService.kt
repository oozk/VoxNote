package com.offlinetranscriber.transcription

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleService
import androidx.lifecycle.lifecycleScope
import com.offlinetranscriber.OfflineTranscriberApp
import com.offlinetranscriber.R
import com.offlinetranscriber.ui.MainActivity
import com.offlinetranscriber.util.Format
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.flow.sample
import kotlinx.coroutines.launch

/**
 * Foreground service that keeps long transcriptions running when the app is in the
 * background or the screen is off, with a progress notification and a Cancel action.
 */
class TranscriptionService : LifecycleService() {
    companion object {
        private const val ACTION_RUN = "run"
        private const val ACTION_CANCEL = "cancel"
        private const val CHANNEL = "transcription"
        private const val CHANNEL_DONE = "transcription_done"
        private const val NOTIFICATION_ID = 1

        fun start(context: Context) {
            ContextCompat.startForegroundService(context, Intent(context, TranscriptionService::class.java).setAction(ACTION_RUN))
        }

        fun cancel(context: Context) {
            context.startService(Intent(context, TranscriptionService::class.java).setAction(ACTION_CANCEL))
        }
    }

    private var worker: Job? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private val container get() = (application as OfflineTranscriberApp).container

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)
        ensureChannels()
        val type = when {
            Build.VERSION.SDK_INT >= 35 -> ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROCESSING
            Build.VERSION.SDK_INT >= 29 -> ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
            else -> 0
        }
        ServiceCompat.startForeground(this, NOTIFICATION_ID, progressNotification("Starting…", null, 0f), type)
        when (intent?.action) {
            ACTION_CANCEL -> container.runner.cancelCurrent()
            else -> if (worker?.isActive != true) startWorker()
        }
        return START_NOT_STICKY
    }

    @OptIn(FlowPreview::class)
    private fun startWorker() {
        wakeLock = getSystemService(PowerManager::class.java)
            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "OfflineTranscriber:transcribe").apply { acquire(8 * 3600_000L) }
        val notifier = lifecycleScope.launch {
            container.runner.state.filterNotNull().sample(1000).collect { s ->
                val text = buildString {
                    append(s.phase.label)
                    if (s.chunkCount > 1) append(" · part ${s.chunkIndex + 1}/${s.chunkCount}")
                    s.etaMs?.let { append(" · ").append(Format.eta(it)).append(" left") }
                }
                getSystemService(NotificationManager::class.java)
                    .notify(NOTIFICATION_ID, progressNotification(text, s.title, s.fraction))
            }
        }
        worker = lifecycleScope.launch {
            try {
                container.runner.runQueue()
            } finally {
                notifier.cancel()
                wakeLock?.takeIf { it.isHeld }?.release()
                notifyFinished()
                ServiceCompat.stopForeground(this@TranscriptionService, ServiceCompat.STOP_FOREGROUND_REMOVE)
                stopSelf()
            }
        }
    }

    private suspend fun notifyFinished() {
        val last = container.transcriptions.history.firstOrNull()?.maxByOrNull { it.updatedAt } ?: return
        val text = when (last.status) {
            com.offlinetranscriber.data.JobStatus.COMPLETED -> "Transcription finished"
            com.offlinetranscriber.data.JobStatus.CANCELLED -> "Transcription cancelled"
            else -> "Transcription stopped: ${last.error ?: last.status}"
        }
        val open = PendingIntent.getActivity(this, 3,
            Intent(this, MainActivity::class.java).putExtra(MainActivity.EXTRA_ROUTE, "transcript/${last.id}"),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val n = NotificationCompat.Builder(this, CHANNEL_DONE)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(text)
            .setContentText(last.title)
            .setAutoCancel(true)
            .setContentIntent(open)
            .build()
        getSystemService(NotificationManager::class.java).notify(1000 + last.id.toInt(), n)
    }

    override fun onDestroy() {
        wakeLock?.takeIf { it.isHeld }?.release()
        super.onDestroy()
    }

    private fun ensureChannels() {
        val nm = getSystemService(NotificationManager::class.java)
        if (nm.getNotificationChannel(CHANNEL) == null) {
            nm.createNotificationChannel(NotificationChannel(CHANNEL, "Transcription progress", NotificationManager.IMPORTANCE_LOW))
        }
        if (nm.getNotificationChannel(CHANNEL_DONE) == null) {
            nm.createNotificationChannel(NotificationChannel(CHANNEL_DONE, "Finished transcriptions", NotificationManager.IMPORTANCE_DEFAULT))
        }
    }

    private fun progressNotification(text: String, title: String?, fraction: Float): Notification {
        val open = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java).putExtra(MainActivity.EXTRA_ROUTE, "transcribe"),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val cancel = PendingIntent.getService(this, 1, Intent(this, TranscriptionService::class.java).setAction(ACTION_CANCEL),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        return NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(title ?: "Transcribing on this device")
            .setContentText(text)
            .setProgress(1000, (fraction * 1000).toInt(), fraction <= 0f)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(open)
            .addAction(0, "Cancel", cancel)
            .build()
    }
}
