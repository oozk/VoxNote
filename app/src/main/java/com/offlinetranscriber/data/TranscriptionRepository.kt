package com.offlinetranscriber.data

import android.net.Uri
import androidx.room.withTransaction
import com.offlinetranscriber.audio.AudioDecoder
import com.offlinetranscriber.audio.Wav
import com.offlinetranscriber.export.ExportDocument
import com.offlinetranscriber.export.ExportSegment
import com.offlinetranscriber.util.AppStorage
import com.offlinetranscriber.util.Format
import com.offlinetranscriber.util.Languages
import com.offlinetranscriber.vocab.CorrectionEngine
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withContext
import java.io.File

class TranscriptionRepository(private val db: AppDatabase, private val storage: AppStorage) {
    private val jobs = db.transcriptions()
    private val segments = db.segments()
    private val suggestions = db.suggestions()

    val history: Flow<List<TranscriptionEntity>> = jobs.observeAll()
    val previews: Flow<List<TranscriptPreview>> = jobs.observePreviews()

    fun observe(id: Long) = jobs.observe(id)
    fun observeSegments(id: Long) = segments.observeFor(id)
    fun observeSuggestions(id: Long) = suggestions.observeFor(id)
    suspend fun get(id: Long) = jobs.get(id)
    suspend fun segmentsOf(id: Long) = segments.forTranscription(id)
    suspend fun update(t: TranscriptionEntity) = jobs.update(t.copy(updatedAt = System.currentTimeMillis()))

    suspend fun createFromRecording(file: File, title: String, durationMs: Long): Long {
        val ready = Wav.readInfo(file)?.isWhisperReady == true
        return jobs.insert(TranscriptionEntity(title = title, audioPath = file.absolutePath, audioDurationMs = durationMs,
            pcmPath = if (ready) file.absolutePath else null, status = JobStatus.NOT_STARTED))
    }

    /** Copies shared/imported audio into app storage so the transcript keeps a link to it. */
    suspend fun importAudio(uri: Uri, isCancelled: () -> Boolean = { false }, onProgress: (Long, Long) -> Unit = { _, _ -> }): Long =
        withContext(Dispatchers.IO) {
            val name = storage.displayName(uri) ?: "audio"
            val size = storage.sizeOf(uri)
            if (size > 0 && size + 20L * 1024 * 1024 > storage.freeBytes()) throw IllegalStateException("Not enough free storage to import this file.")
            val dest = storage.uniqueFile(storage.audioDir, name)
            storage.copyUriTo(uri, dest, isCancelled) { onProgress(it, size) }
            val duration = AudioDecoder.probeDurationMs(dest)
            val ready = Wav.readInfo(dest)?.isWhisperReady == true
            jobs.insert(TranscriptionEntity(title = name.substringBeforeLast('.'), audioPath = dest.absolutePath,
                audioDurationMs = duration, pcmPath = if (ready) dest.absolutePath else null, status = JobStatus.NOT_STARTED))
        }

    /**
     * Queues a job. A job that has completed, or whose settings changed, restarts from
     * scratch; an interrupted job with the same settings resumes where it stopped.
     */
    suspend fun queue(id: Long, modelId: Long?, language: String?, categories: List<String>?, restart: Boolean = false,
                      identifySpeakers: Boolean? = null) {
        val t = jobs.get(id) ?: return
        val settingsChanged = (modelId != null && modelId != t.modelId) ||
            (language != null && language != t.languageRequested) ||
            (categories != null && categories.joinToString("\n") != t.vocabularyCategories) ||
            (identifySpeakers != null && identifySpeakers != t.identifySpeakers)
        val fresh = restart || t.status == JobStatus.COMPLETED || settingsChanged || t.chunkPlan == null
        db.withTransaction {
            if (fresh) {
                suggestions.deleteFor(id)
                segments.deleteFor(id)
            }
            jobs.update(t.copy(
                modelId = modelId ?: t.modelId,
                languageRequested = language ?: t.languageRequested,
                vocabularyCategories = categories?.joinToString("\n") ?: t.vocabularyCategories,
                identifySpeakers = identifySpeakers ?: t.identifySpeakers,
                status = JobStatus.QUEUED, error = null,
                nextChunk = if (fresh) 0 else t.nextChunk,
                chunkPlan = if (fresh) null else t.chunkPlan,
                previousText = if (fresh) null else t.previousText,
                seenTermIds = if (fresh) "" else t.seenTermIds,
                languageDetected = if (fresh) null else t.languageDetected,
                progress = if (fresh) 0f else t.progress,
                processingMs = if (fresh) 0 else t.processingMs,
                updatedAt = System.currentTimeMillis(),
            ))
        }
    }

    suspend fun nextQueued(): TranscriptionEntity? = jobs.withStatus(listOf(JobStatus.QUEUED)).minByOrNull { it.updatedAt }

    /** After a process restart nothing can be running: mark such jobs as interrupted (resumable). */
    suspend fun markInterrupted(processStartMs: Long) {
        for (t in jobs.withStatus(JobStatus.ACTIVE.toList()).filter { it.updatedAt < processStartMs }) {
            jobs.update(t.copy(status = JobStatus.INTERRUPTED, error = "Processing was interrupted. Tap Resume to continue."))
        }
    }

    suspend fun workFilesToKeep(): Set<String> =
        jobs.withStatus(listOf(JobStatus.INTERRUPTED, JobStatus.FAILED, JobStatus.CANCELLED, JobStatus.QUEUED))
            .mapNotNull { it.pcmPath }.toSet()

    suspend fun rename(id: Long, title: String) {
        val t = jobs.get(id) ?: return
        jobs.update(t.copy(title = title.trim().ifEmpty { t.title }, updatedAt = System.currentTimeMillis()))
    }

    suspend fun delete(id: Long) = withContext(Dispatchers.IO) {
        val t = jobs.get(id) ?: return@withContext
        jobs.delete(id)
        File(t.audioPath).delete()
        t.pcmPath?.let { p -> if (p != t.audioPath) File(p).delete() }
    }

    // ---------- speakers ----------

    suspend fun renameSpeaker(tid: Long, speaker: Int, name: String) {
        val t = jobs.get(tid) ?: return
        val map = t.speakerNameMap().toMutableMap()
        if (name.isBlank()) map.remove(speaker) else map[speaker] = name.trim().replace('\n', ' ')
        jobs.update(t.copy(speakerNames = map.entries.sortedBy { it.key }.joinToString("\n") { "${it.key}=${it.value}" }))
    }

    /** All parts of speaker [from] become speaker [to] (fixes one person split into two). */
    suspend fun mergeSpeakers(tid: Long, from: Int, to: Int) {
        if (from == to) return
        segments.mergeSpeaker(tid, from, to)
        renameSpeaker(tid, from, "")
    }

    suspend fun setSegmentSpeaker(segmentId: Long, speaker: Int?) {
        val s = segments.get(segmentId) ?: return
        segments.update(s.copy(speaker = speaker))
    }

    // ---------- editing ----------

    suspend fun editSegment(segmentId: Long, text: String) {
        val s = segments.get(segmentId) ?: return
        segments.update(s.copy(text = text))
    }

    suspend fun restoreOriginal(segmentId: Long) {
        val s = segments.get(segmentId) ?: return
        s.originalText?.let { segments.update(s.copy(text = it)) }
    }

    /** Applies an accepted correction to the current segment text (which may have been edited since). */
    suspend fun acceptSuggestion(id: Long): Boolean = db.withTransaction {
        val sug = suggestions.get(id) ?: return@withTransaction false
        val seg = segments.get(sug.segmentId) ?: return@withTransaction false
        val at = CorrectionEngine.locate(seg.text, sug.original, sug.startOffset)
        if (at < 0) {
            suggestions.update(sug.copy(status = SuggestionStatus.STALE))
            return@withTransaction false
        }
        segments.update(seg.copy(text = seg.text.replaceRange(at, at + sug.original.length, sug.replacement)))
        suggestions.update(sug.copy(status = SuggestionStatus.ACCEPTED))
        true
    }

    suspend fun rejectSuggestion(id: Long) {
        val s = suggestions.get(id) ?: return
        suggestions.update(s.copy(status = SuggestionStatus.REJECTED))
    }

    /** Reverts an automatically applied or accepted correction. */
    suspend fun undoSuggestion(id: Long) = db.withTransaction {
        val sug = suggestions.get(id) ?: return@withTransaction
        val seg = segments.get(sug.segmentId) ?: return@withTransaction
        val at = CorrectionEngine.locate(seg.text, sug.replacement, sug.startOffset)
        if (at >= 0) segments.update(seg.copy(text = seg.text.replaceRange(at, at + sug.replacement.length, sug.original)))
        suggestions.update(sug.copy(status = SuggestionStatus.REJECTED))
    }

    suspend fun acceptAll(tid: Long): Int {
        var n = 0
        for (s in suggestions.withStatus(tid, SuggestionStatus.PENDING).sortedByDescending { it.startOffset }) {
            if (acceptSuggestion(s.id)) n++
        }
        return n
    }

    /** Find & replace across the whole transcript. Returns the number of replacements. */
    suspend fun replaceAll(tid: Long, find: String, replace: String, matchCase: Boolean, wholeWord: Boolean): Int {
        if (find.isEmpty()) return 0
        val regex = findRegex(find, matchCase, wholeWord)
        var count = 0
        val changed = segments.forTranscription(tid).mapNotNull { s ->
            val n = regex.findAll(s.text).count()
            if (n == 0) null else { count += n; s.copy(text = regex.replace(s.text, Regex.escapeReplacement(replace))) }
        }
        if (changed.isNotEmpty()) segments.updateAll(changed)
        return count
    }

    suspend fun exportDocument(tid: Long, includeMetadata: Boolean = true): ExportDocument {
        val t = jobs.get(tid) ?: error("Transcription not found")
        val segs = segments.forTranscription(tid).map { ExportSegment(it.startMs, it.endMs, it.text, t.speakerName(it.speaker)) }
        val meta = if (!includeMetadata) emptyList() else listOfNotNull(
            "Date" to Format.date(t.createdAt),
            "Duration" to Format.duration(t.audioDurationMs),
            t.modelName?.let { "Model" to it },
            "Language" to Languages.name(t.languageDetected ?: t.languageRequested),
            "Audio file" to File(t.audioPath).name,
            "Transcribed locally with" to "VoxNote (${t.modelName ?: "on-device model"})",
        )
        return ExportDocument(t.title, segs, meta)
    }

    companion object {
        fun findRegex(find: String, matchCase: Boolean, wholeWord: Boolean): Regex {
            val p = Regex.escape(find).let { if (wholeWord) "(?<![\\p{L}\\p{N}])$it(?![\\p{L}\\p{N}])" else it }
            return if (matchCase) Regex(p) else Regex(p, setOf(RegexOption.IGNORE_CASE))
        }
    }
}
