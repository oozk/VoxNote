package com.offlinetranscriber.engine

import java.io.DataInputStream
import java.io.File
import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

/** Model file formats we can recognise from the first bytes of a file. */
enum class ModelFormat(val label: String) {
    GGML_WHISPER("GGML (whisper.cpp)"),
    GGUF("GGUF"),
    SAFETENSORS("safetensors"),
    PYTORCH_ZIP("PyTorch checkpoint"),
    ONNX("ONNX"),
    HTML("HTML web page"),
    UNKNOWN("Unknown"),
}

data class WhisperHParams(
    val nVocab: Int,
    val nAudioCtx: Int,
    val nAudioState: Int,
    val nAudioHead: Int,
    val nAudioLayer: Int,
    val nTextCtx: Int,
    val nTextState: Int,
    val nTextHead: Int,
    val nTextLayer: Int,
    val nMels: Int,
    val ftype: Int,
)

data class ModelInspection(
    val format: ModelFormat,
    val compatible: Boolean,
    val message: String,
    val hparams: WhisperHParams? = null,
    /** e.g. "tiny", "base", "small", "medium", "large-v3", "large-v3-turbo" */
    val sizeClass: String? = null,
    /** e.g. "F16", "Q5_0" */
    val quantization: String? = null,
    val multilingual: Boolean? = null,
) {
    val summary: String
        get() = buildString {
            append(format.label)
            sizeClass?.let { append(" · ").append(it) }
            quantization?.let { append(" · ").append(it) }
            multilingual?.let { append(if (it) " · multilingual" else " · English-only") }
        }
}

/**
 * Verifies model compatibility from the file header instead of trusting the file name.
 * A header check is necessary but not sufficient: the final proof is a successful
 * native load ([WhisperEngine.load]), which the Model Manager performs as "Verify".
 */
object ModelInspector {
    private const val GGML_MAGIC = 0x67676d6c   // "ggml", little-endian uint32 as written by whisper.cpp
    private const val GGUF_MAGIC = 0x46554747   // "GGUF"
    private const val QNT_VERSION_FACTOR = 1000

    /** ggml_ftype values that whisper.cpp's CPU backend can load. */
    private val FTYPES = mapOf(
        0 to "F32", 1 to "F16", 2 to "Q4_0", 3 to "Q4_1", 7 to "Q8_0", 8 to "Q5_0", 9 to "Q5_1",
        10 to "Q2_K", 11 to "Q3_K", 12 to "Q4_K", 13 to "Q5_K", 14 to "Q6_K",
    )

    fun inspect(file: File): ModelInspection =
        if (!file.exists() || !file.canRead()) {
            ModelInspection(ModelFormat.UNKNOWN, false, "File not found or not readable.")
        } else file.inputStream().use { inspect(it, file.length()) }

    fun inspect(input: InputStream, fileSize: Long): ModelInspection {
        val header = ByteArray(64)
        val n = DataInputStream(input).let { readFully(it, header) }
        if (n < 48) return ModelInspection(ModelFormat.UNKNOWN, false, "File is too small to be a speech model.")
        val bb = ByteBuffer.wrap(header).order(ByteOrder.LITTLE_ENDIAN)
        val magic = bb.getInt(0)
        return when {
            magic == GGML_MAGIC -> inspectGgml(bb, fileSize)
            magic == GGUF_MAGIC -> ModelInspection(
                ModelFormat.GGUF, false,
                "This is a GGUF file. The bundled whisper.cpp engine loads Whisper models only in " +
                    "its GGML .bin format (for example ggml-base.bin from huggingface.co/ggerganov/whisper.cpp). " +
                    "GGUF files are typically text LLMs, not Whisper speech models.",
            )
            header[0] == 'P'.code.toByte() && header[1] == 'K'.code.toByte() -> ModelInspection(
                ModelFormat.PYTORCH_ZIP, false,
                "This looks like a PyTorch checkpoint (.pt/.bin zip). Convert it to GGML with " +
                    "whisper.cpp's convert-pt-to-ggml.py, or download a ready-made ggml-*.bin file.",
            )
            looksLikeSafetensors(bb, fileSize) -> ModelInspection(
                ModelFormat.SAFETENSORS, false,
                "This is a safetensors file (Hugging Face Transformers format). Convert it with " +
                    "whisper.cpp's convert-h5-to-ggml.py, or download a ready-made ggml-*.bin file.",
            )
            String(header, 0, 15, Charsets.ISO_8859_1).trimStart().lowercase().let {
                it.startsWith("<!doctype") || it.startsWith("<html")
            } -> ModelInspection(
                ModelFormat.HTML, false,
                "This file is a web page, not a model. The download probably saved the Hugging Face " +
                    "page instead of the file: use the \"download\" (↓) link on the file's page.",
            )
            header[0] == 0x08.toByte() && fileSize > 1_000_000 -> ModelInspection(
                ModelFormat.ONNX, false, "This looks like an ONNX model, which this engine cannot run.",
            )
            else -> ModelInspection(ModelFormat.UNKNOWN, false, "Unrecognised file format: not a whisper.cpp GGML model.")
        }
    }

    private fun inspectGgml(bb: ByteBuffer, fileSize: Long): ModelInspection {
        val hp = WhisperHParams(
            nVocab = bb.getInt(4), nAudioCtx = bb.getInt(8), nAudioState = bb.getInt(12),
            nAudioHead = bb.getInt(16), nAudioLayer = bb.getInt(20), nTextCtx = bb.getInt(24),
            nTextState = bb.getInt(28), nTextHead = bb.getInt(32), nTextLayer = bb.getInt(36),
            nMels = bb.getInt(40), ftype = bb.getInt(44),
        )
        val sane = hp.nVocab in 50_000..52_000 && hp.nAudioCtx == 1500 && hp.nTextCtx == 448 &&
            (hp.nMels == 80 || hp.nMels == 128) && hp.nAudioLayer in 1..64 && hp.nTextLayer in 1..64 &&
            hp.nAudioState in 64..4096 && hp.nAudioHead in 1..64
        if (!sane) {
            return ModelInspection(
                ModelFormat.GGML_WHISPER, false,
                "GGML file, but its parameters do not describe a Whisper speech model " +
                    "(vocab=${hp.nVocab}, mels=${hp.nMels}, layers=${hp.nAudioLayer}).",
                hparams = hp,
            )
        }
        val baseFtype = hp.ftype % QNT_VERSION_FACTOR
        val quant = FTYPES[baseFtype]
            ?: return ModelInspection(
                ModelFormat.GGML_WHISPER, false,
                "Whisper GGML model with unsupported weight type ($baseFtype).", hparams = hp,
            )
        val size = sizeClass(hp)
        val multilingual = hp.nVocab >= 51865
        val minExpected = minimumExpectedBytes(hp, quant)
        if (fileSize in 1 until minExpected) {
            return ModelInspection(
                ModelFormat.GGML_WHISPER, false,
                "The file is smaller than a complete $size model should be. The download was " +
                    "probably interrupted — download it again.",
                hp, size, quant, multilingual,
            )
        }
        return ModelInspection(
            ModelFormat.GGML_WHISPER, true,
            "Compatible Whisper model ($size, $quant, ${if (multilingual) "multilingual" else "English-only"}).",
            hp, size, quant, multilingual,
        )
    }

    fun sizeClass(hp: WhisperHParams): String = when (hp.nAudioLayer) {
        4 -> "tiny"
        6 -> "base"
        12 -> "small"
        24 -> "medium"
        32 -> when {
            hp.nTextLayer == 4 -> "large-v3-turbo"
            hp.nMels == 128 -> "large-v3"
            else -> "large"
        }
        else -> "custom (${hp.nAudioLayer} layers)"
    }

    /** Very conservative lower bound of the weight data size, to catch truncated downloads. */
    private fun minimumExpectedBytes(hp: WhisperHParams, quant: String): Long {
        val params = 12L * hp.nAudioLayer * hp.nAudioState * hp.nAudioState +
            12L * hp.nTextLayer * hp.nTextState * hp.nTextState
        val bitsPerWeight = when (quant) {
            "F32" -> 32.0; "F16" -> 16.0; "Q8_0" -> 8.5; "Q5_0", "Q5_1", "Q5_K" -> 5.5
            "Q4_0", "Q4_1", "Q4_K" -> 4.5; "Q6_K" -> 6.5; "Q3_K" -> 3.4; else -> 2.6
        }
        return (params * bitsPerWeight / 8.0 * 0.8).toLong()
    }

    private fun looksLikeSafetensors(bb: ByteBuffer, fileSize: Long): Boolean {
        val headerLen = bb.getLong(0)
        return headerLen in 2..100_000_000 && headerLen < fileSize && bb.get(8) == '{'.code.toByte()
    }

    private fun readFully(input: DataInputStream, buf: ByteArray): Int {
        var off = 0
        while (off < buf.size) {
            val r = input.read(buf, off, buf.size - off)
            if (r <= 0) break
            off += r
        }
        return off
    }
}

/** RAM estimate based on whisper.cpp's published memory figures. */
object MemoryEstimator {
    private const val MB = 1024L * 1024L

    fun estimateRequiredBytes(fileSizeBytes: Long, sizeClass: String?): Long {
        val overhead = when {
            sizeClass == null -> 400 * MB
            sizeClass.startsWith("tiny") -> 160 * MB
            sizeClass.startsWith("base") -> 220 * MB
            sizeClass.startsWith("small") -> 380 * MB
            sizeClass.startsWith("medium") -> 650 * MB
            sizeClass == "large-v3-turbo" -> 650 * MB
            sizeClass.startsWith("large") -> 1000 * MB
            else -> 500 * MB
        }
        return fileSizeBytes + overhead
    }

    sealed interface Verdict {
        data object Ok : Verdict
        data class Tight(val requiredBytes: Long, val availableBytes: Long) : Verdict
        data class TooLarge(val requiredBytes: Long, val availableBytes: Long, val totalBytes: Long) : Verdict
    }

    /**
     * [availableBytes] = memory the OS reports as available now; [totalBytes] = device RAM.
     * A model that needs more than ~60 % of total RAM is refused (Android will kill the app).
     */
    fun check(requiredBytes: Long, availableBytes: Long, totalBytes: Long): Verdict = when {
        requiredBytes > totalBytes * 0.6 -> Verdict.TooLarge(requiredBytes, availableBytes, totalBytes)
        requiredBytes > availableBytes * 0.9 -> Verdict.Tight(requiredBytes, availableBytes)
        else -> Verdict.Ok
    }
}
