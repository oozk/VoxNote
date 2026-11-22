package com.offlinetranscriber.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

class ModelInspectorTest {
    private fun ggmlHeader(vocab: Int = 51865, audioLayers: Int = 6, textLayers: Int = 6, state: Int = 512, mels: Int = 80, ftype: Int = 1): ByteArray {
        val bb = ByteBuffer.allocate(64).order(ByteOrder.LITTLE_ENDIAN)
        bb.putInt(0x67676d6c)
        listOf(vocab, 1500, state, 8, audioLayers, 448, state, 8, textLayers, mels, ftype).forEach { bb.putInt(it) }
        return bb.array()
    }

    private fun inspect(bytes: ByteArray, size: Long = 200_000_000) = ModelInspector.inspect(ByteArrayInputStream(bytes), size)

    @Test fun recognisesWhisperBaseF16() {
        val r = inspect(ggmlHeader())
        assertTrue(r.message, r.compatible)
        assertEquals("base", r.sizeClass)
        assertEquals("F16", r.quantization)
        assertEquals(true, r.multilingual)
    }

    @Test fun recognisesQuantizedEnglishAndLargeV3Turbo() {
        val en = inspect(ggmlHeader(vocab = 51864, ftype = 2008))   // Q5_0 with quantization version 2
        assertTrue(en.compatible)
        assertEquals("Q5_0", en.quantization)
        assertEquals(false, en.multilingual)
        val turbo = inspect(ggmlHeader(vocab = 51866, audioLayers = 32, textLayers = 4, state = 1280, mels = 128, ftype = 8), 600_000_000)
        assertEquals("large-v3-turbo", turbo.sizeClass)
    }

    @Test fun ggufIsHandedToTheMetadataCheck() {
        val bytes = "GGUF".toByteArray() + ByteArray(60)
        val head = inspect(bytes)                       // header only: not decided yet
        assertFalse(head.compatible)
        assertEquals(ModelFormat.GGUF, head.format)
        assertEquals(ENGINE_TRANSCRIBE, head.engine)
        val full = ModelInspector.inspect(ByteArrayInputStream(bytes), 200_000_000) { ByteArrayInputStream(bytes) }
        assertFalse(full.compatible)                    // version 0 header: damaged
        assertTrue(full.message, full.message.contains("damaged"))
    }

    @Test fun rejectsOtherFormats() {
        assertEquals(ModelFormat.PYTORCH_ZIP, inspect("PK".toByteArray() + ByteArray(62)).format)
        assertEquals(ModelFormat.HTML, inspect("<!DOCTYPE html><html>".toByteArray() + ByteArray(50)).format)
        val st = ByteBuffer.allocate(64).order(ByteOrder.LITTLE_ENDIAN).putLong(100).put('{'.code.toByte()).array()
        assertEquals(ModelFormat.SAFETENSORS, inspect(st).format)
        assertFalse(inspect(ByteArray(10)).compatible)
    }

    @Test fun rejectsNonWhisperGgmlAndTruncatedFiles() {
        assertFalse(inspect(ggmlHeader(vocab = 32000)).compatible)          // a GGML LLM, not Whisper
        val truncated = inspect(ggmlHeader(), size = 1_000_000)               // base model of 1 MB
        assertFalse(truncated.compatible)
        assertTrue(truncated.message.contains("interrupted"))
    }

    @Test fun memoryVerdicts() {
        val gb = 1L shl 30
        val need = MemoryEstimator.estimateRequiredBytes(500L shl 20, "small")
        assertTrue(MemoryEstimator.check(need, 3 * gb, 6 * gb) is MemoryEstimator.Verdict.Ok)
        assertTrue(MemoryEstimator.check(need, 500L shl 20, 6 * gb) is MemoryEstimator.Verdict.Tight)
        val large = MemoryEstimator.estimateRequiredBytes(3 * gb, "large-v3")
        assertTrue(MemoryEstimator.check(large, 3 * gb, 4 * gb) is MemoryEstimator.Verdict.TooLarge)
    }
}
