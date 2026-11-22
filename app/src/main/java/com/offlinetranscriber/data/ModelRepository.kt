package com.offlinetranscriber.data

import android.content.Context
import android.content.Intent
import android.net.Uri
import com.offlinetranscriber.engine.EngineManager
import com.offlinetranscriber.engine.ModelInspection
import com.offlinetranscriber.engine.ModelInspector
import com.offlinetranscriber.util.AppStorage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withContext
import java.io.File

class ModelRepository(
    private val context: Context,
    private val db: AppDatabase,
    private val storage: AppStorage,
    private val engines: EngineManager,
    private val settings: SettingsRepository,
) {
    private val dao = db.models()
    val models: Flow<List<ModelEntity>> = dao.observeAll()

    suspend fun get(id: Long) = dao.get(id)
    suspend fun all() = dao.all()

    /** Inspects a file chosen by the user before it is imported. */
    suspend fun inspectUri(uri: Uri): ModelInspection = withContext(Dispatchers.IO) {
        context.contentResolver.openInputStream(uri).use { input ->
            requireNotNull(input) { "Cannot open file" }
            ModelInspector.inspect(input, storage.sizeOf(uri))
        }
    }

    /**
     * Copies a model into app storage. Incompatible files are rejected before copying.
     * Returns the new model id.
     */
    suspend fun importCopy(uri: Uri, displayName: String?, isCancelled: () -> Boolean, onProgress: (Long, Long) -> Unit): Long =
        withContext(Dispatchers.IO) {
            val inspection = inspectUri(uri)
            if (!inspection.compatible) throw IllegalArgumentException(inspection.message)
            val name = storage.displayName(uri) ?: "model.bin"
            val size = storage.sizeOf(uri)
            if (size > 0 && size + 50L * 1024 * 1024 > storage.freeBytes()) {
                throw IllegalStateException("Not enough free storage to copy this model.")
            }
            val dest = storage.uniqueFile(storage.modelsDir, name)
            storage.copyUriTo(uri, dest, isCancelled) { onProgress(it, size) }
            val after = ModelInspector.inspect(dest)
            if (!after.compatible) { dest.delete(); throw IllegalArgumentException(after.message) }
            insert(displayName?.ifBlank { null } ?: name.removeSuffix(".bin"), dest.absolutePath, null, dest.length(), after)
        }

    /** Registers a model without copying it (keeps a persistable read permission to the document). */
    suspend fun registerInPlace(uri: Uri, displayName: String?, notes: String?): Long = withContext(Dispatchers.IO) {
        val inspection = inspectUri(uri)
        if (!inspection.compatible) throw IllegalArgumentException(inspection.message)
        runCatching { context.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION) }
            .onFailure { throw IllegalStateException("This file location does not allow permanent access. Use \"Import (copy)\" instead.") }
        val name = storage.displayName(uri) ?: "model.bin"
        insert(displayName?.ifBlank { null } ?: name.removeSuffix(".bin"), null, uri.toString(), storage.sizeOf(uri), inspection, notes)
    }

    private suspend fun insert(name: String, path: String?, uri: String?, size: Long, i: ModelInspection, notes: String? = null): Long {
        val id = dao.insert(
            ModelEntity(displayName = name, filePath = path, contentUri = uri, sizeBytes = size, format = i.format.label,
                sizeClass = i.sizeClass, quantization = i.quantization, multilingual = i.multilingual,
                compatible = i.compatible, compatibilityMessage = i.message, notes = notes)
        )
        if (settings.current().defaultModelId == null) settings.update { it.copy(defaultModelId = id) }
        return id
    }

    /** Proves compatibility by actually loading the model in the native engine. */
    suspend fun verify(model: ModelEntity): Result<String> {
        val allowLow = settings.current().allowLowMemoryLoad
        val r = runCatching { engines.verify(model, allowLow) }
        dao.update(model.copy(verified = if (r.isSuccess) 1 else -1, verifyMessage = r.fold({ it }, { it.message ?: "Load failed" })))
        return r
    }

    suspend fun rename(model: ModelEntity, name: String) = dao.update(model.copy(displayName = name.trim().ifEmpty { model.displayName }))

    suspend fun setDefault(id: Long) = settings.update { it.copy(defaultModelId = id) }

    suspend fun remove(model: ModelEntity) = withContext(Dispatchers.IO) {
        if (engines.loadedModelKey?.startsWith("${model.id}:") == true) engines.release()
        model.filePath?.let { File(it).delete() }
        model.contentUri?.let { u ->
            runCatching { context.contentResolver.releasePersistableUriPermission(Uri.parse(u), Intent.FLAG_GRANT_READ_URI_PERMISSION) }
        }
        dao.delete(model)
        val s = settings.current()
        if (s.defaultModelId == model.id) {
            val next = dao.all().firstOrNull { it.compatible }?.id
            settings.update { it.copy(defaultModelId = next) }
        }
    }

    fun modelsStorageBytes(): Long = storage.dirSize(storage.modelsDir)
}
