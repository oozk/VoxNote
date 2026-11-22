package com.offlinetranscriber.audio

import android.annotation.SuppressLint
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder

/**
 * Microphone capture delivering 16 kHz mono float frames (~100 ms) on a background thread.
 * Opens 16 kHz directly when the device supports it, otherwise 48/44.1 kHz with resampling.
 */
class MicCapture(private val onFrames: (FloatArray) -> Unit, private val onError: (String) -> Unit = {}) {
    @Volatile private var running = false
    private var thread: Thread? = null

    @SuppressLint("MissingPermission")
    fun start(): Boolean {
        val (rec, rate) = open() ?: return false
        running = true
        thread = Thread({
            val resampler = StreamingResampler(rate)
            val buf = ShortArray(rate / 10)
            try {
                rec.startRecording()
                while (running) {
                    val n = rec.read(buf, 0, buf.size)
                    if (n <= 0) continue
                    val f = FloatArray(n) { buf[it] / 32768f }
                    onFrames(if (resampler.isPassthrough) f else resampler.process(f))
                }
            } catch (t: Throwable) {
                onError(t.message ?: "Microphone error")
            } finally {
                runCatching { rec.stop() }
                rec.release()
            }
        }, "mic-capture").apply { start() }
        return true
    }

    fun stop() {
        running = false
        thread?.join(1000)
        thread = null
    }

    companion object {
        @SuppressLint("MissingPermission")
        fun open(): Pair<AudioRecord, Int>? {
            for (rate in intArrayOf(WHISPER_SAMPLE_RATE, 48_000, 44_100)) {
                for (source in intArrayOf(MediaRecorder.AudioSource.VOICE_RECOGNITION, MediaRecorder.AudioSource.MIC)) {
                    val min = AudioRecord.getMinBufferSize(rate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
                    if (min <= 0) continue
                    val r = try {
                        AudioRecord(source, rate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, maxOf(min * 4, rate))
                    } catch (e: Exception) { null } ?: continue
                    if (r.state == AudioRecord.STATE_INITIALIZED) return r to rate
                    r.release()
                }
            }
            return null
        }
    }
}

/** Pauses other apps' audio (music, videos) while recording, using transient audio focus. */
class AudioFocusPauser(context: android.content.Context) {
    private val am = context.getSystemService(android.content.Context.AUDIO_SERVICE) as android.media.AudioManager
    private var request: android.media.AudioFocusRequest? = null

    fun pause() {
        if (request != null) return
        val r = android.media.AudioFocusRequest.Builder(android.media.AudioManager.AUDIOFOCUS_GAIN_TRANSIENT)
            .setAudioAttributes(android.media.AudioAttributes.Builder()
                .setUsage(android.media.AudioAttributes.USAGE_ASSISTANT)
                .setContentType(android.media.AudioAttributes.CONTENT_TYPE_SPEECH).build())
            .setOnAudioFocusChangeListener { }
            .build()
        am.requestAudioFocus(r)
        request = r
    }

    fun resume() {
        request?.let { am.abandonAudioFocusRequest(it) }
        request = null
    }
}
