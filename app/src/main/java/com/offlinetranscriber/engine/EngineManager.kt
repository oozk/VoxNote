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
 * Owns the single model that may be in memory. Loading a different model always
 * releases the previous one first, so two large models are never resident together.
 */
class EngineManager(private val context: Context) {
    private val mutex = Mutex()
    private var engine: WhisperEngine? = null
    private var loadedKey: String? = null
    @Volatile var busy = false
        private set

    val loadedModelKey: String? get() = loadedKey

    fun systemInfo(): String = runCatching { WhisperNative.ensureLoaded(); WhisperEngine.systemInfo() }.getOrDefault("unavailable")

    /** Returns a loaded engine for [model], loading it if necessary. */
    suspend fun acquire(model: ModelEntity, flashAttention: Boolean, allowLowMemory: Boolean): WhisperEngine = mutex.withLock {
        val key = "${model.id}:${model.filePath ?: model.contentUri}:$flashAttention"
        engine?.let { if (loadedKey == key) return it }
        releaseLocked()
        checkMemory(model, allowLowMemory)
        val e = withContext(Dispatchers.IO) { loadFromModel(model, flashAttention) }
        engine = e
        loadedKey = key
        e
    }

    fun markBusy(b: Boolean) { busy = b }

    /** Loads the model to prove the engine can use it, then frees it. Returns a description. */
    suspend fun verify(model: ModelEntity, allowLowMemory: Boolean): String = mutex.withLock {
        releaseLocked()
        checkMemory(model, allowLowMemory)
        withContext(Dispatchers.IO) {
            val e = loadFromModel(model, flashAttention = true)
            try {
                val tokens = e.countTokens("Hello world")
                check(tokens > 0) { "Tokenizer self-test failed" }
                "Loaded successfully: ${e.modelType} model, ${if (e.isMultilingual) "multilingual" else "English-only"}."
            } finally {
                e.release()
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
    }

    private fun checkMemory(model: ModelEntity, allowLowMemory: Boolean) {
        val required = MemoryEstimator.estimateRequiredBytes(model.sizeBytes, model.sizeClass)
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

    private fun loadFromModel(model: ModelEntity, flashAttention: Boolean): WhisperEngine {
        WhisperNative.ensureLoaded()
        model.filePath?.let { path ->
            if (!File(path).exists()) throw EngineException("The model file is missing. Remove it and import it again.")
            return WhisperEngine.load(path, flashAttention)
        }
        val uri = model.contentUri?.let(Uri::parse) ?: throw EngineException("Model has no file")
        val pfd: ParcelFileDescriptor = try {
            context.contentResolver.openFileDescriptor(uri, "r")
        } catch (e: Exception) {
            null
        } ?: throw EngineException("The model file can no longer be opened (was it moved or deleted?). Register it again.")
        // whisper.cpp reads the whole model during init, so the descriptor can be closed afterwards
        return pfd.use { WhisperEngine.load("/proc/self/fd/${it.fd}", flashAttention) }
    }
}
