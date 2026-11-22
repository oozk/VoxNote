package com.offlinetranscriber.util

import android.app.ActivityManager
import android.content.Context
import android.net.Uri
import android.os.StatFs
import android.provider.OpenableColumns
import java.io.File
import java.io.InputStream
import java.io.OutputStream

/** All app files live in app-private storage; nothing is written to shared storage unless the user exports. */
class AppStorage(private val context: Context) {
    val modelsDir: File get() = File(context.filesDir, "models").apply { mkdirs() }
    val audioDir: File get() = File(context.filesDir, "audio").apply { mkdirs() }
    /** Decoded 16 kHz PCM for in-progress jobs (kept until the job completes, so it can resume). */
    val workDir: File get() = File(context.filesDir, "work").apply { mkdirs() }
    val exportDir: File get() = File(context.cacheDir, "exports").apply { mkdirs() }
    val tempDir: File get() = File(context.cacheDir, "tmp").apply { mkdirs() }

    fun freeBytes(): Long = StatFs(context.filesDir.absolutePath).availableBytes
    fun totalBytes(): Long = StatFs(context.filesDir.absolutePath).totalBytes
    fun dirSize(dir: File): Long = dir.walkBottomUp().filter { it.isFile }.sumOf { it.length() }

    fun displayName(uri: Uri): String? = runCatching {
        context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
            if (c.moveToFirst()) c.getString(0) else null
        }
    }.getOrNull() ?: uri.lastPathSegment?.substringAfterLast('/')

    fun sizeOf(uri: Uri): Long = runCatching {
        context.contentResolver.query(uri, arrayOf(OpenableColumns.SIZE), null, null, null)?.use { c ->
            if (c.moveToFirst() && !c.isNull(0)) c.getLong(0) else -1L
        }
    }.getOrNull() ?: -1L

    /**
     * Copies [uri] into [dest] via a temporary file (an interrupted copy never leaves a
     * half-written file under the final name). [onProgress] receives bytes copied.
     */
    fun copyUriTo(uri: Uri, dest: File, isCancelled: () -> Boolean = { false }, onProgress: (Long) -> Unit = {}) {
        val tmp = File(dest.parentFile, dest.name + ".part")
        try {
            context.contentResolver.openInputStream(uri).use { input ->
                requireNotNull(input) { "Cannot open the selected file" }
                tmp.outputStream().use { out -> copy(input, out, isCancelled, onProgress) }
            }
            if (dest.exists()) dest.delete()
            check(tmp.renameTo(dest)) { "Could not save file" }
        } finally {
            if (tmp.exists()) tmp.delete()
        }
    }

    /** Removes leftovers from interrupted operations. [keepWork] = work files of resumable jobs. */
    fun cleanupTemp(keepWork: Set<String>, olderThanMs: Long) {
        // only files from before this process started: imports may already be running
        fun stale(f: File) = f.lastModified() < olderThanMs
        tempDir.listFiles()?.filter(::stale)?.forEach { it.deleteRecursively() }
        val dayAgo = System.currentTimeMillis() - 24 * 3600_000L
        exportDir.listFiles()?.filter { it.lastModified() < dayAgo }?.forEach { it.delete() }
        listOf(modelsDir, audioDir).forEach { d -> d.listFiles()?.filter { it.name.endsWith(".part") && stale(it) }?.forEach { it.delete() } }
        workDir.listFiles()?.filter { it.absolutePath !in keepWork && stale(it) }?.forEach { it.delete() }
    }

    fun uniqueFile(dir: File, wanted: String): File {
        val safe = sanitize(wanted)
        var f = File(dir, safe)
        val base = safe.substringBeforeLast('.', safe)
        val ext = safe.substringAfterLast('.', "").let { if (it.isEmpty() || it == safe) "" else ".$it" }
        var i = 2
        while (f.exists()) f = File(dir, "$base ($i)$ext").also { i++ }
        return f
    }

    companion object {
        fun sanitize(name: String): String =
            name.replace(Regex("""[\\/:*?"<>|\u0000-\u001f]"""), "_").trim().ifEmpty { "file" }.take(120)

        fun copy(input: InputStream, out: OutputStream, isCancelled: () -> Boolean = { false }, onProgress: (Long) -> Unit = {}) {
            val buf = ByteArray(1 shl 20)
            var total = 0L
            var lastReport = 0L
            while (true) {
                if (isCancelled()) throw java.util.concurrent.CancellationException("Copy cancelled")
                val n = input.read(buf)
                if (n < 0) break
                out.write(buf, 0, n)
                total += n
                if (total - lastReport > 4 shl 20) { onProgress(total); lastReport = total }
            }
            onProgress(total)
        }
    }
}

data class MemoryStatus(val totalBytes: Long, val availableBytes: Long, val lowMemory: Boolean)

object DeviceInfo {
    fun memory(context: Context): MemoryStatus {
        val am = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        val mi = ActivityManager.MemoryInfo()
        am.getMemoryInfo(mi)
        return MemoryStatus(mi.totalMem, mi.availMem, mi.lowMemory)
    }

    fun cpuCount(): Int = Runtime.getRuntime().availableProcessors()

    /** Model size recommendation for the device's RAM. */
    fun recommendation(totalRam: Long): String {
        val gb = totalRam / (1024.0 * 1024 * 1024)
        return when {
            gb < 3.5 -> "Recommended: tiny or base models (quantized q5/q8)."
            gb < 5.5 -> "Recommended: base or small models. Quantized (q5_1/q8_0) models use less memory."
            gb < 7.5 -> "Recommended: small, or medium quantized (q5_0). large-v3-turbo q5_0 may work."
            else -> "Your device can run medium models and large-v3-turbo (quantized)."
        }
    }
}
