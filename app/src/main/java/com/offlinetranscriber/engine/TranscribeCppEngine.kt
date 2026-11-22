package com.offlinetranscriber.engine

import com.offlinetranscriber.engine.tc.TranscribeNative

/**
 * [SpeechEngine] backed by transcribe.cpp (GGUF models: Parakeet, Whisper GGUF, Moonshine,
 * Canary, SenseVoice, Granite, …). Vocabulary support depends on the model family:
 * some accept a context prompt and/or a native vocabulary list, many (e.g. Parakeet)
 * accept neither — then only VoxNote's local text post-processing applies.
 */
class TranscribeCppEngine private constructor(
    private var handle: Long,
    override val modelPath: String,
    private val info: Map<String, String>,
) : SpeechEngine {

    override val modelType: String = listOf(info["arch"], info["variant"]).filter { !it.isNullOrBlank() }.joinToString(" ")
    override val isMultilingual: Boolean =
        info["language_detect"] == "1" || (info["languages"]?.split(',')?.count { it.isNotBlank() } ?: 0) > 1
    override val capabilities = EngineCapabilities(
        engineName = "transcribe.cpp",
        prompt = info["prompt"] == "1",
        decoderBoost = false,
        vocabularyList = info["vocabulary"] == "1",
        vad = false,
        speakers = info["diarization"] == "1",
        progress = false,
    )
    val languages: List<String> = info["languages"]?.split(',')?.filter { it.isNotBlank() }.orEmpty()

    @Volatile private var running = false

    companion object {
        /** [noMmap] copies weights into memory (needed when the file descriptor is closed after loading). */
        fun load(path: String, threads: Int, noMmap: Boolean = false): TranscribeCppEngine {
            TranscribeNative.ensureLoaded()
            val h = TranscribeNative.nativeOpen(path, threads, noMmap)
            if (h == 0L) {
                val st = TranscribeNative.nativeLastStatus()
                throw EngineException("transcribe.cpp could not load this model: ${TranscribeNative.nativeStatusString(st)}")
            }
            val info = TranscribeNative.nativeModelInfo(h).lines().mapNotNull { l ->
                l.indexOf('=').takeIf { it > 0 }?.let { l.substring(0, it) to l.substring(it + 1) }
            }.toMap()
            return TranscribeCppEngine(h, path, info)
        }

        fun version(): String = runCatching { TranscribeNative.ensureLoaded(); TranscribeNative.nativeVersion() }.getOrDefault("unavailable")
    }

    private fun h(): Long { check(handle != 0L) { "Engine already released" }; return handle }

    override fun transcribe(samples: FloatArray, options: EngineOptions, onSegment: ((EngineSegment) -> Unit)?): EngineResult {
        val h = h()
        running = true
        val vocab = if (capabilities.vocabularyList) options.boostTerms.take(100).toTypedArray() else null
        val st = try {
            TranscribeNative.nativeRun(h, samples, options.language.takeIf { it != "auto" },
                options.initialPrompt?.takeIf { capabilities.prompt }, vocab, options.timestamps)
        } finally {
            running = false
        }
        when (st) {
            TranscribeNative.OK -> Unit
            TranscribeNative.ERR_ABORTED -> throw TranscriptionCancelledException()
            else -> throw EngineException("Speech recognition failed: ${TranscribeNative.nativeStatusString(st)}")
        }
        val n = TranscribeNative.nativeSegmentCount(h)
        val segments = if (n > 0) (0 until n).map { i ->
            val t = TranscribeNative.nativeSegmentTiming(h, i)
            EngineSegment(t[0], t[1], TranscribeNative.nativeSegmentText(h, i), speaker = t[2].toInt().takeIf { it > 0 })
        } else {
            // families without segment timestamps: one segment spanning the audio
            val text = TranscribeNative.nativeFullText(h)
            if (text.isBlank()) emptyList() else listOf(EngineSegment(0, samples.size * 1000L / 16_000, text))
        }
        segments.forEach { onSegment?.invoke(it) }
        return EngineResult(segments, TranscribeNative.nativeDetectedLanguage(h).takeIf { it.isNotBlank() })
    }

    override fun cancel() { if (handle != 0L) TranscribeNative.nativeCancel(handle) }

    override fun progress(): Int = if (running) 0 else 100

    override fun countTokens(text: String): Int =
        if (text.isEmpty()) 0 else TranscribeNative.nativeTokenize(h(), text).takeIf { it >= 0 } ?: PromptEstimate.tokens(text)

    @Synchronized override fun release() {
        if (handle != 0L) { TranscribeNative.nativeClose(handle); handle = 0L }
    }
}

/** Speaker diarization with a Sortformer GGUF model (transcribe.cpp). */
class SpeakerDiarizer private constructor(private var handle: Long, val modelPath: String) {
    data class Turn(val startMs: Long, val endMs: Long, val speaker: Int)

    companion object {
        fun load(path: String, threads: Int): SpeakerDiarizer {
            TranscribeNative.ensureLoaded()
            val h = TranscribeNative.nativeDiarizeOpen(path, threads)
            if (h == 0L) throw EngineException("Could not load the speaker model: ${TranscribeNative.nativeStatusString(TranscribeNative.nativeLastStatus())}")
            return SpeakerDiarizer(h, path)
        }
    }

    fun run(samples: FloatArray): List<Turn> {
        check(handle != 0L)
        val flat = TranscribeNative.nativeDiarizeRun(handle, samples)
            ?: throw EngineException("Speaker detection failed: ${TranscribeNative.nativeStatusString(TranscribeNative.nativeLastStatus())}")
        return (flat.indices step 3).map { Turn(flat[it], flat[it + 1], flat[it + 2].toInt()) }
    }

    fun cancel() { if (handle != 0L) TranscribeNative.nativeDiarizeCancel(handle) }

    @Synchronized fun release() {
        if (handle != 0L) { TranscribeNative.nativeDiarizeClose(handle); handle = 0L }
    }
}

internal object PromptEstimate {
    fun tokens(text: String) = maxOf(text.split(Regex("""\s+""")).count { it.isNotEmpty() }, (text.length + 3) / 4)
}
