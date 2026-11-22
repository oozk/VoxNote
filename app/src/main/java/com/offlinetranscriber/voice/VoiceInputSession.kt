package com.offlinetranscriber.voice

import android.Manifest
import android.content.pm.PackageManager
import androidx.core.content.ContextCompat
import com.offlinetranscriber.AppContainer
import com.offlinetranscriber.audio.ArrayPcmSource
import com.offlinetranscriber.audio.AudioFocusPauser
import com.offlinetranscriber.audio.ChunkPlanner
import com.offlinetranscriber.audio.EnergyEndpointer
import com.offlinetranscriber.audio.MicCapture
import com.offlinetranscriber.engine.EngineOptions
import com.offlinetranscriber.engine.ROLE_ASR
import com.offlinetranscriber.engine.VadModel
import com.offlinetranscriber.transcription.ChunkListener
import com.offlinetranscriber.transcription.ChunkResult
import com.offlinetranscriber.transcription.ChunkedTranscriber
import com.offlinetranscriber.transcription.VocabularyAssist
import com.offlinetranscriber.vocab.CorrectionEngine
import com.offlinetranscriber.vocab.TextPipeline
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.runInterruptible
import java.util.concurrent.atomic.AtomicBoolean

/**
 * One dictation: listen until the speaker stops (or [stop] is called), then transcribe
 * on the device with the default model and the user's vocabulary, replacements and
 * clean-up. Shared by the voice-input popup, the system speech service and the keyboard.
 */
class VoiceInputSession(
    private val c: AppContainer,
    private val scope: CoroutineScope,
    private val listener: Listener,
    /** Language hint from the calling app, e.g. "en-US" (null = app setting). */
    private val languageHint: String? = null,
    /** Stop automatically after silence (keyboards may prefer manual stop). */
    private val autoStop: Boolean = true,
) {
    interface Listener {
        fun onReady() {}
        fun onSpeechStart() {}
        /** Level in dBFS (about -60 quiet … 0 loud). */
        fun onLevel(db: Float) {}
        fun onEndOfSpeech() {}
        fun onTranscribing() {}
        fun onResult(text: String)
        fun onError(code: Int, message: String)
    }

    companion object {
        const val ERROR_NO_PERMISSION = 1
        const val ERROR_NO_MODEL = 2
        const val ERROR_AUDIO = 3
        const val ERROR_NO_SPEECH = 4
        const val ERROR_ENGINE = 5
        const val ERROR_BUSY = 6
        private const val MAX_SECONDS = 180

        private val active = AtomicBoolean(false)
    }

    private val samples = ArrayList<FloatArray>()
    private var totalSamples = 0
    private var mic: MicCapture? = null
    private var transcriber: ChunkedTranscriber? = null

    private val stopped = AtomicBoolean(false)
    private val cancelled = AtomicBoolean(false)
    private val focus = AudioFocusPauser(c.context)

    fun start() {
        if (ContextCompat.checkSelfPermission(c.context, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            listener.onError(ERROR_NO_PERMISSION, "Microphone permission is needed. Open VoxNote once and allow the microphone.")
            return
        }
        if (!active.compareAndSet(false, true)) {
            listener.onError(ERROR_BUSY, "Voice input is already running.")
            return
        }
        scope.launch {
            val settings = c.settings.current()
            val endpointer = EnergyEndpointer(endSilenceMs = settings.voiceSilenceMs, noSpeechTimeoutMs = if (autoStop) 8000 else 0)
            if (settings.pauseOtherAudio) focus.pause()
            // warm the model up while the user speaks
            val modelJob = launch(Dispatchers.IO) { runCatching { loadEngine() } }
            mic = MicCapture(onFrames = { f ->
                if (stopped.get()) return@MicCapture
                synchronized(samples) { samples.add(f); totalSamples += f.size }
                when (endpointer.push(f)) {
                    EnergyEndpointer.Event.SPEECH_START -> listener.onSpeechStart()
                    EnergyEndpointer.Event.SPEECH_END -> if (autoStop) stop()
                    EnergyEndpointer.Event.NO_SPEECH -> if (autoStop) finishWithoutSpeech()
                    null -> Unit
                }
                listener.onLevel(endpointer.levelDb)
                if (totalSamples > MAX_SECONDS * 16_000) stop()
            }, onError = { listener.onError(ERROR_AUDIO, it); release() })
            if (mic?.start() != true) {
                release()
                listener.onError(ERROR_AUDIO, "The microphone could not be opened. Is another app using it?")
                return@launch
            }
            listener.onReady()
            modelJob.join()
        }
    }

    /** Stops listening and transcribes what was said. */
    fun stop() {
        if (!stopped.compareAndSet(false, true)) return
        scope.launch(Dispatchers.Default) {
            mic?.stop()
            listener.onEndOfSpeech()
            transcribeCaptured()
        }
    }

    fun cancel() {
        cancelled.set(true)
        stopped.set(true)
        transcriber?.cancel()

        scope.launch(Dispatchers.Default) { mic?.stop(); release() }
    }

    private fun finishWithoutSpeech() {
        if (!stopped.compareAndSet(false, true)) return
        scope.launch(Dispatchers.Default) {
            mic?.stop()
            release()
            listener.onError(ERROR_NO_SPEECH, "No speech was heard.")
        }
    }

    private suspend fun loadEngine() = run {
        val settings = c.settings.current()
        val model = (settings.defaultModelId?.let { c.models.get(it) }?.takeIf { it.role == ROLE_ASR })
            ?: c.models.all().firstOrNull { it.role == ROLE_ASR && it.compatible }
            ?: throw IllegalStateException("No speech model installed. Open VoxNote › Models to add one.")
        c.engines.acquire(model, settings.flashAttention, settings.allowLowMemoryLoad, settings.effectiveThreads())
    }

    private suspend fun transcribeCaptured() {
        try {
            val audio = synchronized(samples) {
                FloatArray(totalSamples).also { out -> var o = 0; for (s in samples) { s.copyInto(out, o); o += s.size } }
            }
            val range = EnergyEndpointer.speechRange(audio)
            if (range.isEmpty() || audio.size < 16_000 / 4) {
                listener.onError(ERROR_NO_SPEECH, "No speech was heard."); return
            }
            val clip = audio.copyOfRange(range.first, range.last + 1)
            listener.onTranscribing()
            val engine = try { loadEngine() } catch (e: Exception) {
                listener.onError(ERROR_NO_MODEL, e.message ?: "No model"); return
            }
            c.engines.markBusy(true)
            val settings = c.settings.current()
            val terms = c.vocabulary.termsFor(emptyList())
            val language = languageHint?.substringBefore('-')?.substringBefore('_')?.lowercase()
                ?.takeIf { it.length in 2..3 } ?: settings.language
            val source = ArrayPcmSource(clip)
            val t = ChunkedTranscriber(engine).also { transcriber = it }
            val results = ArrayList<ChunkResult>()
            runInterruptible(Dispatchers.Default) {
                t.run(source, ChunkPlanner.plan(source, 120), 0,
                    EngineOptions(threads = settings.effectiveThreads(), language = language, timestamps = true,
                        beamSize = settings.quality.beamSize, vad = settings.skipSilence,
                        vadModelPath = if (settings.skipSilence) VadModel.path(c.context) else null),
                    VocabularyAssist(terms, settings.vocabularyPrompt, settings.vocabularyBoost.strength,
                        settings.suggestCorrections, settings.languageStarter),
                    object : ChunkListener { override fun onChunkCompleted(result: ChunkResult) { results.add(result) } })
            }
            if (cancelled.get()) return
            val pipeline = TextPipeline(c.vocabulary.replacer(), settings.removeFillers)
            val text = results.flatMap { r ->
                val bySeg = r.suggestions.groupBy { it.segmentIndex }
                r.segments.mapIndexed { i, s ->
                    // dictation has no review step: apply only corrections at/above the auto-apply threshold
                    val auto = if (settings.autoApplyCorrections) bySeg[i].orEmpty().filter { it.confidence >= settings.autoApplyThreshold } else emptyList()
                    pipeline.process(CorrectionEngine.apply(s.text, auto).trim(), r.detectedLanguage ?: language)
                }
            }.filter { it.isNotBlank() }.joinToString(" ").trim()
            c.vocabulary.recordHits(results.lastOrNull()?.recognizedTermIds.orEmpty())
            if (text.isEmpty()) listener.onError(ERROR_NO_SPEECH, "No speech was recognised.") else listener.onResult(text)
        } catch (e: Exception) {
            if (!cancelled.get()) listener.onError(ERROR_ENGINE, e.message ?: "Transcription failed")
        } finally {
            c.engines.markBusy(false)
            release()
        }
    }

    private fun release() {
        focus.resume()
        active.set(false)
    }
}
