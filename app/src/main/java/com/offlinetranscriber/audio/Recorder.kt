package com.offlinetranscriber.audio

import android.annotation.SuppressLint
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.File
import kotlin.math.log10
import kotlin.math.sqrt

sealed interface RecorderState {
    data object Idle : RecorderState
    data class Recording(val name: String, val file: File, val elapsedMs: Long, val level: Float, val paused: Boolean) : RecorderState
    data class Saved(val name: String, val file: File, val durationMs: Long, val transcriptionId: Long?, val transcribeNow: Boolean) : RecorderState
    data class Error(val message: String) : RecorderState
}

/**
 * Records 16-bit mono PCM straight into a WAV file at 16 kHz when the device supports
 * it (then no conversion is needed before transcription), otherwise at 48/44.1 kHz.
 * Local only — the microphone stream is never sent anywhere.
 */
class Recorder {
    private val _state = MutableStateFlow<RecorderState>(RecorderState.Idle)
    val state: StateFlow<RecorderState> = _state.asStateFlow()

    @Volatile private var paused = false
    @Volatile private var stopRequested = false
    private var thread: Thread? = null

    val isActive: Boolean get() = _state.value is RecorderState.Recording

    @SuppressLint("MissingPermission")  // permission is requested by the UI before starting
    fun start(name: String, file: File, onFinished: (file: File, durationMs: Long) -> Unit) {
        check(!isActive) { "Already recording" }
        val (record, rate) = openAudioRecord() ?: run {
            _state.value = RecorderState.Error("The microphone could not be opened. Is another app using it?")
            return
        }
        paused = false
        stopRequested = false
        _state.value = RecorderState.Recording(name, file, 0, 0f, false)
        thread = Thread({ loop(record, rate, name, file, onFinished) }, "recorder").apply { start() }
    }

    fun pause() { paused = true }
    fun resume() { paused = false }
    fun stop() { stopRequested = true }

    fun reset() { if (!isActive) _state.value = RecorderState.Idle }

    fun publish(s: RecorderState) { _state.value = s }

    private fun loop(record: AudioRecord, rate: Int, name: String, file: File, onFinished: (File, Long) -> Unit) {
        val buf = ShortArray(rate / 10)  // 100 ms
        var written = 0L
        var lastHeaderFlush = System.currentTimeMillis()
        var wasPaused = false
        try {
            WavWriter(file, rate).use { writer ->
                record.startRecording()
                while (!stopRequested) {
                    if (paused) {
                        if (!wasPaused) { record.stop(); wasPaused = true; writer.flushHeader() }
                        _state.value = RecorderState.Recording(name, file, written * 1000 / rate, 0f, true)
                        Thread.sleep(100)
                        continue
                    }
                    if (wasPaused) { record.startRecording(); wasPaused = false }
                    val n = record.read(buf, 0, buf.size)
                    if (n <= 0) continue
                    writer.write(buf, n)
                    written += n
                    _state.value = RecorderState.Recording(name, file, written * 1000 / rate, level(buf, n), false)
                    if (System.currentTimeMillis() - lastHeaderFlush > 5000) {
                        writer.flushHeader()  // a crash still leaves a playable WAV
                        lastHeaderFlush = System.currentTimeMillis()
                    }
                }
            }
            onFinished(file, written * 1000 / rate)
        } catch (t: Throwable) {
            _state.value = RecorderState.Error("Recording failed: ${t.message}")
        } finally {
            runCatching { record.stop() }
            record.release()
        }
    }

    /** RMS level mapped from -60..0 dBFS to 0..1. */
    private fun level(buf: ShortArray, n: Int): Float {
        var sum = 0.0
        for (i in 0 until n) { val v = buf[i] / 32768.0; sum += v * v }
        val rms = sqrt(sum / n)
        if (rms <= 1e-6) return 0f
        val db = 20 * log10(rms)
        return ((db + 60) / 60).toFloat().coerceIn(0f, 1f)
    }

    @SuppressLint("MissingPermission")
    private fun openAudioRecord(): Pair<AudioRecord, Int>? {
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
