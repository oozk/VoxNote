package com.offlinetranscriber.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

class GgufInspectorTest {
    /** Builds a minimal GGUF v3 header with the given string keys and a large tokenizer array. */
    private fun gguf(arch: String, extra: Map<String, String> = emptyMap(), langs: List<String> = emptyList(), tensors: Long = 100): ByteArray {
        val out = ByteArrayOutputStream()
        fun u32(v: Int) = out.write(ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(v).array())
        fun u64(v: Long) = out.write(ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN).putLong(v).array())
        fun str(s: String) { val b = s.toByteArray(); u64(b.size.toLong()); out.write(b) }
        val kv = LinkedHashMap<String, String>()
        kv["general.architecture"] = arch
        kv["general.name"] = "Test $arch"
        kv.putAll(extra)
        out.write("GGUF".toByteArray()); u32(3); u64(tensors); u64((kv.size + 3).toLong())
        for ((k, v) in kv) { str(k); u32(8); str(v) }
        str("general.languages"); u32(9); u32(8); u64(langs.size.toLong()); langs.forEach { str(it) }
        str("general.file_type"); u32(4); u32(7)
        str("tokenizer.tokens"); u32(9); u32(8); u64(5000); repeat(5000) { str("tok$it") }   // skipped
        return out.toByteArray()
    }

    private fun inspect(b: ByteArray, size: Long = 50_000_000) =
        ModelInspector.inspect(ByteArrayInputStream(b), size) { ByteArrayInputStream(b) }

    @Test fun parakeetIsCompatibleSpeechModel() {
        val r = inspect(gguf("parakeet", mapOf("stt.variant" to "tdt-0.6b-v3"), listOf("en", "de", "fr")))
        assertTrue(r.message, r.compatible)
        assertEquals(ENGINE_TRANSCRIBE, r.engine)
        assertEquals(ROLE_ASR, r.role)
        assertEquals("tdt-0.6b-v3", r.sizeClass)
        assertEquals("Q8_0", r.quantization)
        assertEquals(listOf("en", "de", "fr"), r.languages)
        assertEquals("Test parakeet", r.displayName)
    }

    @Test fun sortformerIsSpeakerModel() {
        val r = inspect(gguf("sortformer"))
        assertTrue(r.compatible)
        assertEquals(ROLE_DIARIZE, r.role)
    }

    @Test fun llmAndUnknownArchsAreRejected() {
        val llm = inspect(gguf("llama"))
        assertFalse(llm.compatible)
        assertTrue(llm.message, llm.message.contains("language model"))
        assertFalse(inspect(gguf("someaudio", mapOf("stt.x" to "1"))).compatible)
        assertFalse(inspect(gguf("silero_vad")).compatible)
    }

    @Test fun truncatedOrEmptyGgufIsReported() {
        val b = gguf("parakeet")
        val truncated = b.copyOf(b.size / 2)
        val r = inspect(truncated)
        assertFalse(r.compatible)
        assertTrue(r.message, r.message.contains("damaged"))
        assertFalse(inspect(gguf("parakeet", tensors = 0)).compatible)
    }
}
