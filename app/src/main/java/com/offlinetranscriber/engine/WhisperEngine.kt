package com.offlinetranscriber.engine

/**
 * whisper.cpp-backed [SpeechEngine]. One instance = one model in memory.
 * Call [release] to free the native memory; never keep two large models alive.
 */
class WhisperEngine private constructor(
    private var handle: Long,
    override val modelPath: String,
) : SpeechEngine {

    override val modelType: String = WhisperNative.nativeModelType(handle)
    override val isMultilingual: Boolean = WhisperNative.nativeIsMultilingual(handle)
    override val capabilities = EngineCapabilities(
        engineName = "whisper.cpp", prompt = true, decoderBoost = true, vocabularyList = false,
        vad = true, speakers = false, progress = true,
    )

    companion object {
        /** Loads a model. [WhisperNative] must already be loaded. Throws [EngineException]. */
        fun load(path: String, flashAttention: Boolean = true): WhisperEngine {
            val h = WhisperNative.nativeInit(path, flashAttention)
            if (h == 0L) {
                throw EngineException(
                    "The inference engine could not load this model. The file may be damaged, " +
                        "incomplete, or not a whisper.cpp GGML model."
                )
            }
            return WhisperEngine(h, path)
        }

        fun systemInfo(): String = WhisperNative.nativeSystemInfo()
    }

    private fun requireHandle(): Long {
        check(handle != 0L) { "Engine already released" }
        return handle
    }

    override fun transcribe(
        samples: FloatArray,
        options: EngineOptions,
        onSegment: ((EngineSegment) -> Unit)?,
    ): EngineResult {
        val h = requireHandle()
        val listener = onSegment?.let { cb ->
            object : NativeSegmentListener {
                override fun onSegment(index: Int, startMs: Long, endMs: Long, text: String) {
                    cb(EngineSegment(startMs, endMs, text))
                }
            }
        }
        val rc = WhisperNative.nativeTranscribe(
            h,
            samples,
            options.threads,
            options.language.ifBlank { "auto" },
            options.translateToEnglish,
            options.initialPrompt?.takeIf { it.isNotBlank() },
            options.carryPrompt,
            !options.timestamps,
            options.beamSize,
            options.boostTerms.takeIf { it.isNotEmpty() && options.boostStrength > 0f }?.toTypedArray(),
            options.boostStrength,
            options.vad && options.vadModelPath != null,
            options.vadModelPath,
            listener,
        )
        when (rc) {
            WhisperNative.RC_OK -> Unit
            WhisperNative.RC_CANCELLED -> throw TranscriptionCancelledException()
            else -> throw EngineException("Speech recognition failed (engine error $rc).")
        }
        val n = WhisperNative.nativeSegmentCount(h)
        val segments = (0 until n).map { i ->
            EngineSegment(
                startMs = WhisperNative.nativeSegmentStartMs(h, i),
                endMs = WhisperNative.nativeSegmentEndMs(h, i),
                text = WhisperNative.nativeSegmentText(h, i),
                noSpeechProbability = WhisperNative.nativeSegmentNoSpeechProb(h, i),
                confidence = WhisperNative.nativeSegmentConfidence(h, i).takeIf { it >= 0f },
            )
        }
        val lang = WhisperNative.nativeDetectedLanguage(h).takeIf { it.isNotBlank() }
        return EngineResult(segments, lang)
    }

    override fun cancel() {
        if (handle != 0L) WhisperNative.nativeCancel(handle)
    }

    override fun progress(): Int = if (handle == 0L) 0 else WhisperNative.nativeProgress(handle)

    override fun countTokens(text: String): Int =
        if (text.isEmpty()) 0 else WhisperNative.nativeCountTokens(requireHandle(), text)

    @Synchronized
    override fun release() {
        if (handle != 0L) {
            WhisperNative.nativeFree(handle)
            handle = 0L
        }
    }
}
