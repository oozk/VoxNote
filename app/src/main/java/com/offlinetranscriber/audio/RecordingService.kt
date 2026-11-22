package com.offlinetranscriber.audio

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleService
import androidx.lifecycle.lifecycleScope
import com.offlinetranscriber.OfflineTranscriberApp
import com.offlinetranscriber.R
import com.offlinetranscriber.transcription.TranscriptionService
import com.offlinetranscriber.ui.MainActivity
import com.offlinetranscriber.util.Format
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.sample
import kotlinx.coroutines.launch
import java.io.File

/** Keeps recording alive with the screen off (foreground service of type "microphone"). */
class RecordingService : LifecycleService() {

    companion object {
        private const val ACTION_START = "start"
        private const val ACTION_PAUSE = "pause"
        private const val ACTION_RESUME = "resume"
        private const val ACTION_STOP = "stop"
        private const val EXTRA_NAME = "name"
        private const val EXTRA_TRANSCRIBE = "transcribe"
        private const val CHANNEL = "recording"
        private const val NOTIFICATION_ID = 2

        fun start(context: Context, name: String, transcribeAfter: Boolean) {
            val i = Intent(context, RecordingService::class.java).setAction(ACTION_START)
                .putExtra(EXTRA_NAME, name).putExtra(EXTRA_TRANSCRIBE, transcribeAfter)
            ContextCompat.startForegroundService(context, i)
        }

        fun pause(context: Context) = send(context, ACTION_PAUSE)
        fun resume(context: Context) = send(context, ACTION_RESUME)
        fun stop(context: Context) = send(context, ACTION_STOP)

        private fun send(context: Context, action: String) {
            context.startService(Intent(context, RecordingService::class.java).setAction(action))
        }
    }

    private val container get() = (application as OfflineTranscriberApp).container
    private var transcribeAfter = false

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)
        val recorder = container.recorder
        when (intent?.action) {
            ACTION_START -> {
                ensureChannel()
                val type = if (Build.VERSION.SDK_INT >= 30) ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE else 0
                ServiceCompat.startForeground(this, NOTIFICATION_ID, notification("Recording…", false), type)
                if (recorder.isActive) return START_NOT_STICKY
                val name = intent.getStringExtra(EXTRA_NAME)?.ifBlank { null } ?: defaultName()
                transcribeAfter = intent.getBooleanExtra(EXTRA_TRANSCRIBE, false)
                val file = container.storage.uniqueFile(container.storage.audioDir, "$name.wav")
                val settings = kotlinx.coroutines.runBlocking { container.settings.current() }
                recorder.autoStopSilenceMs = settings.recordingAutoStopSec * 1000
                if (settings.pauseOtherAudio) focus.pause()
                if (settings.liveCaptions) {
                    val cap = container.newCaptioner(settings.language)
                    container.captioner.value = cap
                    recorder.frameListener = { cap.feed(it) }
                } else recorder.frameListener = null
                recorder.start(name, file) { f, durationMs -> onRecordingFinished(name, f, durationMs) }
                lifecycleScope.launch {
                    @OptIn(FlowPreview::class)
                    recorder.state.sample(1000).collect { s ->
                        if (s is RecorderState.Recording) {
                            val text = (if (s.paused) "Paused · " else "Recording · ") + Format.duration(s.elapsedMs)
                            getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, notification(text, s.paused))
                        }
                    }
                }
            }
            ACTION_PAUSE -> recorder.pause()
            ACTION_RESUME -> recorder.resume()
            ACTION_STOP -> recorder.stop()
        }
        return START_NOT_STICKY
    }

    private val focus by lazy { AudioFocusPauser(this) }

    private fun onRecordingFinished(name: String, file: File, durationMs: Long) {
        container.recorder.frameListener = null
        focus.resume()
        container.captioner.value?.finish()
        lifecycleScope.launch {
            val id = runCatching { container.transcriptions.createFromRecording(file, name, durationMs) }.getOrNull()
            if (id != null && transcribeAfter) {
                container.transcriptions.queue(id, null, null, null)
                TranscriptionService.start(this@RecordingService)
            }
            container.recorder.publish(RecorderState.Saved(name, file, durationMs, id, transcribeAfter))
            ServiceCompat.stopForeground(this@RecordingService, ServiceCompat.STOP_FOREGROUND_REMOVE)
            stopSelf()
        }
    }

    private fun defaultName(): String =
        "Recording " + java.text.SimpleDateFormat("yyyy-MM-dd HH.mm.ss", java.util.Locale.getDefault()).format(java.util.Date())

    private fun ensureChannel() {
        val nm = getSystemService(NotificationManager::class.java)
        if (nm.getNotificationChannel(CHANNEL) == null) {
            nm.createNotificationChannel(NotificationChannel(CHANNEL, "Recording", NotificationManager.IMPORTANCE_LOW))
        }
    }

    private fun notification(text: String, paused: Boolean): Notification {
        val open = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java).putExtra(MainActivity.EXTRA_ROUTE, "record"),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        fun action(a: String, req: Int) = PendingIntent.getService(this, req, Intent(this, RecordingService::class.java).setAction(a),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        return NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle("VoxNote")
            .setContentText(text)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(open)
            .addAction(0, if (paused) "Resume" else "Pause", action(if (paused) ACTION_RESUME else ACTION_PAUSE, 1))
            .addAction(0, "Stop", action(ACTION_STOP, 2))
            .build()
    }
}
