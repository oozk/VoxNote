package com.offlinetranscriber.engine

import android.content.Context
import java.io.File

/**
 * The bundled Silero VAD v6.2 model (ggml format, ~0.9 MB, MIT licence) used by
 * whisper.cpp to skip silence. Copied from the APK assets to app storage once.
 */
object VadModel {
    private const val ASSET = "vad/ggml-silero-v6.2.0.bin"

    @Synchronized
    fun path(context: Context): String? = runCatching {
        val f = File(context.filesDir, "vad/ggml-silero-v6.2.0.bin")
        if (!f.exists() || f.length() < 100_000) {
            f.parentFile?.mkdirs()
            val tmp = File(f.parentFile, f.name + ".part")
            context.assets.open(ASSET).use { i -> tmp.outputStream().use { o -> i.copyTo(o) } }
            tmp.renameTo(f)
        }
        f.absolutePath
    }.getOrNull()
}
