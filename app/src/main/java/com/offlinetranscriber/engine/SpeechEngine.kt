package com.offlinetranscriber.engine

/**
 * Abstraction over a loaded local speech-recognition model.
 * Pure Kotlin (no Android imports) so the transcription pipeline can be
 * exercised by JVM tests against the real native library.
 */
interface SpeechEngine {
    val modelPath: String
    val modelType: String
    val isMultilingual: Boolean

    /** Transcribe 16 kHz mono float PCM. Blocks the calling thread. */
    fun transcribe(
        samples: FloatArray,
        options: EngineOptions,
        onSegment: ((EngineSegment) -> Unit)? = null,
    ): EngineResult

    /** Request cancellation of the running [transcribe] call (thread-safe). */
    fun cancel()

    /** Progress of the current [transcribe] call, 0..100. */
    fun progress(): Int

    /** Number of decoder tokens [text] uses (for prompt budgeting). */
    fun countTokens(text: String): Int

    fun release()
}

data class EngineOptions(
    val threads: Int = 4,
    /** ISO code such as "en", or "auto" for automatic detection. */
    val language: String = "auto",
    val translateToEnglish: Boolean = false,
    /** Decoder context ("initial prompt"); used for vocabulary assistance. */
    val initialPrompt: String? = null,
    /** Re-apply [initialPrompt] to every 30 s decoding window. */
    val carryPrompt: Boolean = true,
    val timestamps: Boolean = true,
    /** 1 = greedy decoding, >1 = beam search with that many beams. */
    val beamSize: Int = 1,
    /** Keywords for decoder-side logit boost (empty = disabled). */
    val boostTerms: List<String> = emptyList(),
    /** Logit bonus for continuing a keyword; 0 disables the boost. */
    val boostStrength: Float = 0f,
)

data class EngineSegment(
    val startMs: Long,
    val endMs: Long,
    val text: String,
    val noSpeechProbability: Float = 0f,
)

data class EngineResult(
    val segments: List<EngineSegment>,
    val detectedLanguage: String?,
)

class TranscriptionCancelledException : Exception("Transcription cancelled")
class EngineException(message: String) : Exception(message)
