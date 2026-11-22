package com.offlinetranscriber.transcription

import androidx.room.withTransaction
import com.offlinetranscriber.AppContainer
import com.offlinetranscriber.audio.AudioDecoder
import com.offlinetranscriber.audio.ChunkPlanner
import com.offlinetranscriber.audio.Wav
import com.offlinetranscriber.audio.WavPcmSource
import com.offlinetranscriber.data.AppSettings
import com.offlinetranscriber.data.JobStatus
import com.offlinetranscriber.data.SegmentEntity
import com.offlinetranscriber.data.SuggestionEntity
import com.offlinetranscriber.data.SuggestionStatus
import com.offlinetranscriber.data.TranscriptionEntity
import com.offlinetranscriber.engine.EngineOptions
import com.offlinetranscriber.engine.EngineSegment
import com.offlinetranscriber.engine.TranscriptionCancelledException
import com.offlinetranscriber.vocab.CorrectionEngine
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.withContext
import java.io.File
import java.util.concurrent.CancellationException
import java.util.concurrent.atomic.AtomicBoolean

enum class RunPhase(val label: String) {
    PREPARING_AUDIO("Preparing audio"),
    LOADING_MODEL("Loading model"),
    TRANSCRIBING("Transcribing"),
    FINISHING("Finishing"),
}

data class RunState(
    val jobId: Long,
    val title: String,
    val phase: RunPhase,
    /** 0..1 within the phase (overall for TRANSCRIBING). */
    val fraction: Float = 0f,
    val chunkIndex: Int = 0,
    val chunkCount: Int = 0,
    val etaMs: Long? = null,
    val liveText: String = "",
    val modelName: String? = null,
    val language: String? = null,
    val vocabularyNote: String? = null,
)

/**
 * Processes queued transcription jobs one at a time. Every finished chunk is written
 * to the database in one transaction together with the job's resume point, so a
 * crash, a reboot or a cancellation never loses completed work.
 */
class TranscriptionRunner(private val c: AppContainer) {
    private val _state = MutableStateFlow<RunState?>(null)
    val state: StateFlow<RunState?> = _state.asStateFlow()

    private val cancelRequested = AtomicBoolean(false)
    @Volatile private var transcriber: ChunkedTranscriber? = null

    fun cancelCurrent() {
        cancelRequested.set(true)
        transcriber?.cancel()
    }

    /** Runs until no QUEUED job is left. */
    suspend fun runQueue() {
        while (true) {
            val job = c.transcriptions.nextQueued() ?: break
            cancelRequested.set(false)
            process(job)
        }
        _state.value = null
    }

    private suspend fun process(initial: TranscriptionEntity) = withContext(Dispatchers.Default) {
        var job = initial
        val settings = c.settings.current()
        try {
            val model = (job.modelId ?: settings.defaultModelId)?.let { c.models.get(it) }
                ?: throw IllegalStateException("No speech model selected. Add one on the Models screen.")
            job = save(job.copy(status = JobStatus.PREPARING, modelId = model.id, modelName = model.displayName))
            _state.value = RunState(job.id, job.title, RunPhase.PREPARING_AUDIO, modelName = model.displayName)

            // 1. audio -> 16 kHz mono WAV (decoded once, kept until the job completes)
            val pcm = preparePcm(job)
            job = save(job.copy(pcmPath = pcm.first.absolutePath, audioDurationMs = pcm.second.takeIf { it > 0 } ?: job.audioDurationMs))

            // 2. model
            _state.value = _state.value?.copy(phase = RunPhase.LOADING_MODEL, fraction = 0f)
            val engine = c.engines.acquire(model, settings.flashAttention, settings.allowLowMemoryLoad)
            if (cancelRequested.get()) throw TranscriptionCancelledException()
            c.engines.markBusy(true)

            WavPcmSource(File(job.pcmPath!!)).use { source ->
                // 3. chunk plan (re-used when resuming)
                val chunks = job.chunkPlan?.let(ChunkPlanner::decode)?.takeIf { it.isNotEmpty() && it.last().end == source.sampleCount }
                    ?: ChunkPlanner.plan(source, settings.chunkSeconds).also {
                        job = save(job.copy(chunkPlan = ChunkPlanner.encode(it), nextChunk = 0))
                    }
                val from = job.nextChunk.coerceIn(0, chunks.size)
                if (from == 0) c.db.withTransaction {
                    c.db.suggestions().deleteFor(job.id)
                    c.db.segments().deleteFor(job.id)
                }

                // 4. vocabulary
                val categories = job.vocabularyCategories.lines().filter { it.isNotBlank() }
                val terms = c.vocabulary.termsFor(categories)
                val note = vocabularyNote(settings, terms.size)
                job = save(job.copy(status = JobStatus.RUNNING, vocabularyNote = note))

                val t = ChunkedTranscriber(engine)
                transcriber = t
                if (cancelRequested.get()) throw TranscriptionCancelledException()
                val startFraction = if (chunks.isEmpty()) 0f else chunks[from.coerceAtMost(chunks.lastIndex)].start.toFloat() / source.sampleCount.coerceAtLeast(1)
                val runStart = System.currentTimeMillis()
                val live = StringBuilder()
                _state.value = RunState(job.id, job.title, RunPhase.TRANSCRIBING, startFraction, from, chunks.size,
                    modelName = model.displayName, language = job.languageDetected ?: job.languageRequested, vocabularyNote = note)

                val listener = object : ChunkListener {
                    override fun onChunkStarted(index: Int, total: Int, prompt: String?) {
                        live.setLength(0)
                        _state.value = _state.value?.copy(chunkIndex = index, chunkCount = total, liveText = "")
                    }

                    override fun onLiveSegment(segment: EngineSegment) {
                        if (live.isNotEmpty()) live.append(' ')
                        live.append(segment.text.trim())
                        _state.value = _state.value?.copy(liveText = live.takeLast(600).toString())
                    }

                    override fun onProgress(fraction: Float) {
                        val done = fraction - startFraction
                        val elapsed = System.currentTimeMillis() - runStart
                        val eta = if (done > 0.01f && elapsed > 3000) (elapsed * (1f - fraction) / done).toLong() else null
                        _state.value = _state.value?.copy(fraction = fraction, etaMs = eta)
                    }

                    var lastMark = System.currentTimeMillis()
                    override fun onChunkCompleted(result: ChunkResult) = runBlocking {
                        val now = System.currentTimeMillis()
                        job = persistChunk(job, result, settings, now - lastMark)
                        lastMark = now
                        _state.value = _state.value?.copy(language = job.languageDetected ?: job.languageRequested)
                    }
                }
                val options = EngineOptions(
                    threads = settings.effectiveThreads(),
                    language = job.languageRequested,
                    timestamps = settings.timestamps,
                    beamSize = settings.quality.beamSize,
                )
                runInterruptible(Dispatchers.Default) {
                    t.run(source, chunks, from, options, VocabularyAssist(terms, settings.vocabularyPrompt,
                        settings.vocabularyBoost.strength, settings.suggestCorrections), listener,
                        previousText = job.previousText, seenTermIds = parseIds(job.seenTermIds),
                        knownLanguage = job.languageDetected)
                }
            }

            // 5. done
            _state.value = _state.value?.copy(phase = RunPhase.FINISHING, fraction = 1f, etaMs = null)
            c.vocabulary.recordHits(parseIds(job.seenTermIds))
            val keepPcm = job.pcmPath == job.audioPath
            if (!keepPcm) job.pcmPath?.let { File(it).delete() }
            save(job.copy(status = JobStatus.COMPLETED, progress = 1f, error = null, pcmPath = if (keepPcm) job.pcmPath else null))
        } catch (e: Throwable) {
            val cancelled = e is TranscriptionCancelledException || e is CancellationException || cancelRequested.get()
            val latest = c.transcriptions.get(job.id) ?: job
            save(latest.copy(
                status = if (cancelled) JobStatus.CANCELLED else JobStatus.FAILED,
                error = if (cancelled) "Cancelled. Completed parts are kept; tap Resume to continue." else (e.message ?: e.javaClass.simpleName),
            ))
            if (e is CancellationException && !cancelRequested.get()) throw e  // service scope cancelled
        } finally {
            transcriber = null
            c.engines.markBusy(false)
        }
    }

    private suspend fun preparePcm(job: TranscriptionEntity): Pair<File, Long> = withContext(Dispatchers.IO) {
        job.pcmPath?.let { File(it) }?.takeIf { it.exists() && Wav.readInfo(it)?.isWhisperReady == true }?.let {
            return@withContext it to (Wav.readInfo(it)?.durationMs ?: 0L)
        }
        val audio = File(job.audioPath)
        if (!audio.exists()) throw IllegalStateException("The audio file for this transcription no longer exists.")
        val out = File(c.storage.workDir, "job_${job.id}.wav")
        val r = AudioDecoder.toWhisperWav(audio, out, { cancelRequested.get() }) { f ->
            _state.value = _state.value?.copy(phase = RunPhase.PREPARING_AUDIO, fraction = f)
        }
        if (cancelRequested.get()) throw TranscriptionCancelledException()
        r.wav to r.durationMs
    }

    private suspend fun persistChunk(job: TranscriptionEntity, r: ChunkResult, s: AppSettings, elapsedMs: Long): TranscriptionEntity =
        c.db.withTransaction {
            val segDao = c.db.segments()
            var pos = segDao.maxPosition(job.id) + 1
            // apply high-confidence corrections (only when enabled); keep the original text
            val bySegment = r.suggestions.groupBy { it.segmentIndex }
            val rows = r.segments.mapIndexed { i, seg ->
                val auto = if (s.autoApplyCorrections) bySegment[i].orEmpty().filter { it.confidence >= s.autoApplyThreshold } else emptyList()
                SegmentEntity(transcriptionId = job.id, position = pos++, startMs = seg.startMs, endMs = seg.endMs,
                    originalText = if (s.preserveOriginal) seg.text.trim() else null,
                    text = CorrectionEngine.apply(seg.text, auto).trim())
            }
            val ids = segDao.insertAll(rows)
            val sugg = r.suggestions.mapNotNull { sg ->
                val segId = ids.getOrNull(sg.segmentIndex) ?: return@mapNotNull null
                val autoApplied = s.autoApplyCorrections && sg.confidence >= s.autoApplyThreshold
                val lead = r.segments[sg.segmentIndex].text.length - r.segments[sg.segmentIndex].text.trimStart().length
                SuggestionEntity(transcriptionId = job.id, segmentId = segId, startOffset = (sg.start - lead).coerceAtLeast(0),
                    endOffset = (sg.end - lead).coerceAtLeast(0), original = sg.original, replacement = sg.replacement,
                    termId = sg.termId, confidence = sg.confidence, kind = sg.kind.name,
                    status = if (autoApplied) SuggestionStatus.AUTO_APPLIED else SuggestionStatus.PENDING)
            }
            if (sugg.isNotEmpty()) c.db.suggestions().insertAll(sugg)
            val updated = job.copy(
                nextChunk = r.chunk.index + 1,
                progress = r.chunk.end.toFloat() / (ChunkPlanner.decode(job.chunkPlan ?: "").lastOrNull()?.end ?: r.chunk.end).coerceAtLeast(1),
                previousText = r.segments.joinToString(" ") { it.text.trim() }.takeLast(400).ifBlank { job.previousText },
                seenTermIds = r.recognizedTermIds.joinToString(","),
                languageDetected = r.detectedLanguage ?: job.languageDetected,
                processingMs = job.processingMs + elapsedMs.coerceAtLeast(0),
                updatedAt = System.currentTimeMillis(),
            )
            c.db.transcriptions().update(updated)
            updated
        }

    private suspend fun save(t: TranscriptionEntity): TranscriptionEntity {
        val u = t.copy(updatedAt = System.currentTimeMillis())
        c.db.transcriptions().update(u)
        return u
    }

    private fun parseIds(s: String): Set<Long> = s.split(',').mapNotNull { it.trim().toLongOrNull() }.toSet()

    private fun vocabularyNote(s: AppSettings, terms: Int): String = if (terms == 0) "No vocabulary used." else buildString {
        append("$terms vocabulary terms. ")
        val parts = ArrayList<String>()
        if (s.vocabularyPrompt) parts.add("prompt context (decoder spelling hint)")
        if (s.vocabularyBoost.strength > 0f) parts.add("decoder keyword boost: ${s.vocabularyBoost.label.lowercase()}")
        if (s.suggestCorrections) parts.add("local text post-processing suggestions" + if (s.autoApplyCorrections) " (listed spellings auto-applied)" else "")
        append(if (parts.isEmpty()) "Vocabulary assistance is turned off." else "Used: " + parts.joinToString("; ") + ".")
        append(" None of these change what the acoustic model hears.")
    }
}
