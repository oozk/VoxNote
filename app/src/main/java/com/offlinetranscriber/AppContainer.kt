package com.offlinetranscriber

import android.content.Context
import com.offlinetranscriber.audio.Recorder
import com.offlinetranscriber.data.AppDatabase
import com.offlinetranscriber.data.ModelRepository
import com.offlinetranscriber.data.SettingsRepository
import com.offlinetranscriber.data.TranscriptionRepository
import com.offlinetranscriber.data.VocabularyRepository
import com.offlinetranscriber.engine.EngineManager
import com.offlinetranscriber.transcription.TranscriptionRunner
import com.offlinetranscriber.util.AppStorage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/** Manual dependency container (no DI framework needed for an app of this size). */
class AppContainer(val context: Context) {
    val db: AppDatabase = AppDatabase.create(context)
    val storage = AppStorage(context)
    val settings = SettingsRepository(db.settings())
    val engines = EngineManager(context)
    val vocabulary = VocabularyRepository(db)
    val models = ModelRepository(context, db, storage, engines, settings)
    val transcriptions = TranscriptionRepository(db, storage)
    val runner = TranscriptionRunner(this)
    val recorder = Recorder()
    /** Live captions of the current recording or device-audio capture (null = off). */
    val captioner = kotlinx.coroutines.flow.MutableStateFlow<com.offlinetranscriber.transcription.LiveCaptioner?>(null)

    /** Creates a captioner that uses the default speech model (loaded on first use). */
    fun newCaptioner(language: String): com.offlinetranscriber.transcription.LiveCaptioner {
        var cached: com.offlinetranscriber.engine.SpeechEngine? = null
        val provider = {
            cached ?: kotlinx.coroutines.runBlocking {
                runCatching {
                    val s = settings.current()
                    val model = (s.defaultModelId?.let { models.get(it) }) ?: models.all().firstOrNull { it.role == "asr" && it.compatible }
                    model?.let { engines.acquire(it, s.flashAttention, s.allowLowMemoryLoad, s.effectiveThreads()) }
                }.getOrNull()
            }.also { cached = it }
        }
        return com.offlinetranscriber.transcription.LiveCaptioner(provider,
            com.offlinetranscriber.engine.EngineOptions(threads = 4, language = language))
    }
    private val processStartMs = System.currentTimeMillis()

    /** Runs at startup: seed categories, recover interrupted jobs, remove stale temp files. */
    suspend fun startup() {
        vocabulary.ensureBuiltInCategories()
        transcriptions.markInterrupted(processStartMs)
        withContext(Dispatchers.IO) { storage.cleanupTemp(transcriptions.workFilesToKeep(), processStartMs) }
    }

    /** "Delete all application data": recordings, transcripts, models, vocabulary and settings. */
    suspend fun deleteAllData() = withContext(Dispatchers.IO) {
        runner.cancelCurrent()
        engines.release()
        db.clearAllTables()
        listOf(storage.modelsDir, storage.audioDir, storage.workDir, storage.exportDir, storage.tempDir).forEach { it.deleteRecursively() }
        File(context.cacheDir.absolutePath).listFiles()?.forEach { it.deleteRecursively() }
        vocabulary.ensureBuiltInCategories()
    }
}
