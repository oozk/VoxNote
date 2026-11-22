package com.offlinetranscriber.audio

import android.annotation.SuppressLint
import android.app.Activity
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioPlaybackCaptureConfiguration
import android.media.AudioRecord
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import androidx.annotation.RequiresApi
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleService
import androidx.lifecycle.lifecycleScope
import com.offlinetranscriber.OfflineTranscriberApp
import com.offlinetranscriber.R
import com.offlinetranscriber.ui.MainActivity
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import java.io.File

/**
 * Captions and records audio that other apps play (videos, podcasts, calls on speaker),
 * using Android's playback capture (Android 10+). Apps can opt out of being captured;
 * their audio is then silent. The capture is saved as a recording you can transcribe.
 */
@RequiresApi(29)
class DeviceAudioCaptureService : LifecycleService() {
    companion object {
        private const val EXTRA_RESULT = "result"
        private const val EXTRA_DATA = "data"
        private const val ACTION_STOP = "stop"
        private const val CHANNEL = "device_audio"

        sealed interface State {
            data object Idle : State
            data class Capturing(val elapsedMs: Long, val level: Float) : State
            data class Saved(val transcriptionId: Long?, val durationMs: Long) : State
            data class Error(val message: String) : State
        }
        val state = MutableStateFlow<State>(State.Idle)

        fun start(context: Context, resultCode: Int, data: Intent) {
            ContextCompat.startForegroundService(context, Intent(context, DeviceAudioCaptureService::class.java)
                .putExtra(EXTRA_RESULT, resultCode).putExtra(EXTRA_DATA, data))
        }

        fun stop(context: Context) {
            context.startService(Intent(context, DeviceAudioCaptureService::class.java).setAction(ACTION_STOP))
        }
    }

    private val container get() = (application as OfflineTranscriberApp).container
    @Volatile private var running = false
    private var projection: MediaProjection? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)
        if (intent?.action == ACTION_STOP) { running = false; return START_NOT_STICKY }
        val nm = getSystemService(NotificationManager::class.java)
        if (nm.getNotificationChannel(CHANNEL) == null) nm.createNotificationChannel(NotificationChannel(CHANNEL, "Device audio captions", NotificationManager.IMPORTANCE_LOW))
        val open = PendingIntent.getActivity(this, 5, Intent(this, MainActivity::class.java).putExtra(MainActivity.EXTRA_ROUTE, "live"),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val stop = PendingIntent.getService(this, 6, Intent(this, DeviceAudioCaptureService::class.java).setAction(ACTION_STOP), PendingIntent.FLAG_IMMUTABLE)
        val n = NotificationCompat.Builder(this, CHANNEL).setSmallIcon(R.drawable.ic_notification)
            .setContentTitle("Captioning device audio").setContentText("On-device · tap to open").setOngoing(true)
            .setContentIntent(open).addAction(0, "Stop", stop).build()
        ServiceCompat.startForeground(this, 3, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION)
        if (running) return START_NOT_STICKY
        val code = intent?.getIntExtra(EXTRA_RESULT, Activity.RESULT_CANCELED) ?: Activity.RESULT_CANCELED
        @Suppress("DEPRECATION") val data: Intent? = intent?.getParcelableExtra(EXTRA_DATA)
        if (code != Activity.RESULT_OK || data == null) { state.value = State.Error("Screen-audio permission was not granted."); stopSelf(); return START_NOT_STICKY }
        projection = getSystemService(MediaProjectionManager::class.java).getMediaProjection(code, data)
        projection?.registerCallback(object : MediaProjection.Callback() { override fun onStop() { running = false } }, null)
        capture()
        return START_NOT_STICKY
    }

    @SuppressLint("MissingPermission")
    private fun capture() {
        val p = projection ?: return
        val config = AudioPlaybackCaptureConfiguration.Builder(p)
            .addMatchingUsage(AudioAttributes.USAGE_MEDIA)
            .addMatchingUsage(AudioAttributes.USAGE_GAME)
            .addMatchingUsage(AudioAttributes.USAGE_UNKNOWN)
            .build()
        val format = AudioFormat.Builder().setEncoding(AudioFormat.ENCODING_PCM_16BIT).setSampleRate(WHISPER_SAMPLE_RATE)
            .setChannelMask(AudioFormat.CHANNEL_IN_MONO).build()
        val record = try {
            AudioRecord.Builder().setAudioFormat(format).setAudioPlaybackCaptureConfig(config).setBufferSizeInBytes(WHISPER_SAMPLE_RATE * 2).build()
        } catch (e: Exception) {
            state.value = State.Error("Device audio capture is not available: ${e.message}"); stopSelf(); return
        }
        val name = "Device audio " + java.text.SimpleDateFormat("yyyy-MM-dd HH.mm", java.util.Locale.getDefault()).format(java.util.Date())
        val file = container.storage.uniqueFile(container.storage.audioDir, "$name.wav")
        val settings = kotlinx.coroutines.runBlocking { container.settings.current() }
        val cap = container.newCaptioner(settings.language)
        container.captioner.value = cap
        running = true
        Thread({
            var samples = 0L
            val buf = ShortArray(WHISPER_SAMPLE_RATE / 10)
            try {
                WavWriter(file, WHISPER_SAMPLE_RATE).use { w ->
                    record.startRecording()
                    while (running) {
                        val n = record.read(buf, 0, buf.size)
                        if (n <= 0) continue
                        w.write(buf, n)
                        samples += n
                        val f = FloatArray(n) { buf[it] / 32768f }
                        cap.feed(f)
                        val rms = kotlin.math.sqrt(f.fold(0.0) { a, v -> a + v * v } / n).toFloat()
                        state.value = State.Capturing(samples * 1000 / WHISPER_SAMPLE_RATE, (rms * 4).coerceAtMost(1f))
                        if (samples % (WHISPER_SAMPLE_RATE * 5L) < n) w.flushHeader()
                    }
                }
            } catch (t: Throwable) {
                state.value = State.Error("Capture failed: ${t.message}")
            } finally {
                runCatching { record.stop() }; record.release()
                cap.finish()
                projection?.stop()
                finishCapture(name, file, samples * 1000 / WHISPER_SAMPLE_RATE)
            }
        }, "device-audio").start()
    }

    private fun finishCapture(name: String, file: File, durationMs: Long) {
        lifecycleScope.launch {
            val id = if (durationMs > 1000) runCatching { container.transcriptions.createFromRecording(file, name, durationMs) }.getOrNull()
                     else { file.delete(); null }
            if (state.value !is State.Error) state.value = State.Saved(id, durationMs)
            ServiceCompat.stopForeground(this@DeviceAudioCaptureService, ServiceCompat.STOP_FOREGROUND_REMOVE)
            stopSelf()
        }
    }

    override fun onDestroy() { running = false; super.onDestroy() }
}
