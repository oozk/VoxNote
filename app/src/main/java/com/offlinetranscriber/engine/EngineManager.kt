package com.offlinetranscriber.engine

import android.content.Context
import android.net.Uri
import android.os.ParcelFileDescriptor
import com.offlinetranscriber.data.ModelEntity
import com.offlinetranscriber.util.DeviceInfo
import com.offlinetranscriber.util.Format
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File

class InsufficientMemoryException(message: String) : Exception(message)

/**
 * Owns the single speech model that may be in memory (whisper.cpp or transcribe.cpp) and,
 * separately, the optional speaker model. Loading a different speech model always releases
 * the previous one first, so two large speech models are never resident together.
 */
class EngineManager(private val context: Context) {
    private val mutex = Mutex()
    private var engine: SpeechEngine? = null
    private var loadedKey: String? = null
    private var diarizer: SpeakerDiarizer? = null
    private var diarizerKey: String? = null
    @Volatile var busy = false
        private set

    val loadedModelKey: String? get() = loadedKey

    fun systemInfo(): String = runCatching { WhisperNative.ensureLoaded(); WhisperEngine.systemInfo() }.getOrDefault("unavailable")
    fun secondEngineInfo(): String = TranscribeCppEngine.version()

    /** Returns a loaded engine for [model], loading it if necessary. */
    suspend fun acquire(model: ModelEntity, flashAttention: Boolean, allowLowMemory: Boolean, threads: Int = 4): SpeechEngine = mutex.withLock {
        val key = "${model.id}:${model.filePath ?: model.contentUri}:$flashAttention"
        engine?.let { if (loadedKey == key) return it }
        releaseLocked()
        checkMemory(model, allowLowMemory)
        val e = withContext(Dispatchers.IO) { loadFromModel(model, flashAttention, threads) }
        engine = e
        loadedKey = key
        e
    }

    /** Loads (or reuses) the speaker model. */
    suspend fun acquireDiarizer(model: ModelEntity, threads: Int): SpeakerDiarizer = mutex.withLock {
        val key = "${model.id}:${model.filePath ?: model.contentUri}"
        diarizer?.let { if (diarizerKey == key) return it }
        diarizer?.release()
        diarizer = null
        val d = withContext(Dispatchers.IO) { withModelPath(model) { SpeakerDiarizer.load(it, threads) } }
        diarizer = d
        diarizerKey = key
        d
    }

    fun markBusy(b: Boolean) { busy = b }

    /** Loads the model to prove the engine can use it, then frees it. Returns a description. */
    suspend fun verify(model: ModelEntity, allowLowMemory: Boolean): String = mutex.withLock {
        releaseLocked()
        checkMemory(model, allowLowMemory)
        withContext(Dispatchers.IO) {
            if (model.role == ROLE_DIARIZE) {
                val d = withModelPath(model) { SpeakerDiarizer.load(it, 2) }
                try {
                    d.run(FloatArray(16_000))   // 1 s of silence: proves the graph runs
                    "Loaded successfully: speaker-identification model."
                } finally { d.release() }
            } else {
                val e = loadFromModel(model, flashAttention = true, threads = 2)
                try {
                    val tokens = e.countTokens("Hello world")
                    check(tokens > 0) { "Tokenizer self-test failed" }
                    "Loaded successfully with ${e.capabilities.engineName}: ${e.modelType}, " +
                        (if (e.isMultilingual) "multilingual" else "single-language") + "."
                } finally {
                    e.release()
                }
            }
        }
    }

    suspend fun release() = mutex.withLock { releaseLocked() }

    /** Frees memory when Android asks, unless a transcription is running. */
    fun releaseIfIdle() {
        if (!busy && mutex.tryLock()) {
            try { releaseLocked() } finally { mutex.unlock() }
        }
    }

    private fun releaseLocked() {
        engine?.release()
        engine = null
        loadedKey = null
        diarizer?.release()
        diarizer = null
        diarizerKey = null
    }

    private fun checkMemory(model: ModelEntity, allowLowMemory: Boolean) {
        // transcribe.cpp memory-maps weights (file-backed pages), so they count less
        val fileBytes = if (model.engine == ENGINE_TRANSCRIBE) model.sizeBytes / 2 else model.sizeBytes
        val required = MemoryEstimator.estimateRequiredBytes(fileBytes, model.sizeClass)
        val mem = DeviceInfo.memory(context)
        when (val v = MemoryEstimator.check(required, mem.availableBytes, mem.totalBytes)) {
            is MemoryEstimator.Verdict.Ok -> Unit
            is MemoryEstimator.Verdict.Tight -> if (!allowLowMemory) throw InsufficientMemoryException(
                "This model needs about ${Format.bytes(v.requiredBytes)} of memory but only " +
                    "${Format.bytes(v.availableBytes)} is free right now. Close other apps and try again, " +
                    "choose a smaller/quantized model, or enable \"Allow loading with low memory\" in Settings."
            )
            is MemoryEstimator.Verdict.TooLarge -> throw InsufficientMemoryException(
                "This model needs about ${Format.bytes(v.requiredBytes)} of memory, which is too much for this " +
                    "device (${Format.bytes(v.totalBytes)} RAM). Choose a smaller or quantized model (e.g. q5_0)."
            )
        }
    }

    private fun loadFromModel(model: ModelEntity, flashAttention: Boolean, threads: Int): SpeechEngine =
        withModelPath(model) { path ->
            if (model.engine == ENGINE_TRANSCRIBE) TranscribeCppEngine.load(path, threads, noMmap = model.contentUri != null)
            else { WhisperNative.ensureLoaded(); WhisperEngine.load(path, flashAttention) }
        }

    /**
     * Resolves a model file path. Models registered "in place" are opened through their
     * content URI; the engines read (or map) the file during load, so the descriptor stays
     * open only while loading. Memory-mapped GGUF weights keep their own reference.
     */
    private fun <T> withModelPath(model: ModelEntity, block: (String) -> T): T {
        model.filePath?.let { path ->
            if (!File(path).exists()) throw EngineException("The model file is missing. Remove it and import it again.")
            return block(path)
        }
        val uri = model.contentUri?.let(Uri::parse) ?: throw EngineException("Model has no file")
        val pfd: ParcelFileDescriptor = try {
            context.contentResolver.openFileDescriptor(uri, "r")
        } catch (e: Exception) {
            null
        } ?: throw EngineException("The model file can no longer be opened (was it moved or deleted?). Register it again.")
        return pfd.use { block("/proc/self/fd/${it.fd}") }
    }
}
