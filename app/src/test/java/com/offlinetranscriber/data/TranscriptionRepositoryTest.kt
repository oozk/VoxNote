package com.offlinetranscriber.data

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.offlinetranscriber.export.ExportFormat
import com.offlinetranscriber.export.Exporters
import com.offlinetranscriber.util.AppStorage
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import java.io.File

@RunWith(AndroidJUnit4::class)
@Config(sdk = [33])
class TranscriptionRepositoryTest {
    private lateinit var db: AppDatabase
    private lateinit var repo: TranscriptionRepository
    private var tid = 0L
    private val segIds = ArrayList<Long>()

    @Before fun setUp() = runBlocking {
        val ctx = ApplicationProvider.getApplicationContext<Context>()
        db = AppDatabase.create(ctx, inMemory = true)
        repo = TranscriptionRepository(db, AppStorage(ctx))
        val audio = File(ctx.filesDir, "a.wav").apply { writeBytes(ByteArray(10)) }
        tid = db.transcriptions().insert(TranscriptionEntity(title = "Test", audioPath = audio.absolutePath, status = JobStatus.COMPLETED,
            audioDurationMs = 5000, chunkPlan = "0-80000", nextChunk = 1))
        segIds += db.segments().insertAll(listOf(
            SegmentEntity(transcriptionId = tid, position = 0, startMs = 0, endMs = 2000, originalText = "we used the hplc from thermofisher", text = "we used the hplc from thermofisher"),
            SegmentEntity(transcriptionId = tid, position = 1, startMs = 2000, endMs = 5000, originalText = "Cats and cat food.", text = "Cats and cat food."),
        ))
        db.suggestions().insertAll(listOf(
            SuggestionEntity(transcriptionId = tid, segmentId = segIds[0], startOffset = 12, endOffset = 16, original = "hplc", replacement = "HPLC", termId = 1, confidence = 0.9, kind = "CASE_OR_SPACING"),
            SuggestionEntity(transcriptionId = tid, segmentId = segIds[0], startOffset = 22, endOffset = 34, original = "thermofisher", replacement = "Thermo Fisher", termId = 2, confidence = 0.98, kind = "ALTERNATIVE_SPELLING"),
        ))
    }

    @After fun tearDown() = db.close()

    private suspend fun text(i: Int) = db.segments().get(segIds[i])!!.text
    private suspend fun suggestion(original: String) = db.suggestions().withStatus(tid, SuggestionStatus.PENDING).first { it.original == original }

    @Test fun acceptRejectAndUndoKeepOriginal() = runBlocking {
        val s = suggestion("thermofisher")
        assertTrue(repo.acceptSuggestion(s.id))
        assertEquals("we used the hplc from Thermo Fisher", text(0))
        repo.undoSuggestion(s.id)
        assertEquals("we used the hplc from thermofisher", text(0))
        repo.rejectSuggestion(suggestion("hplc").id)
        assertEquals(SuggestionStatus.REJECTED, db.suggestions().get(s.id)!!.status)
        assertEquals("we used the hplc from thermofisher", db.segments().get(segIds[0])!!.originalText)
    }

    @Test fun acceptAfterUserEditLocatesTextOrMarksStale() = runBlocking {
        repo.editSegment(segIds[0], "Today we used the hplc")
        assertTrue(repo.acceptSuggestion(suggestion("hplc").id))
        assertEquals("Today we used the HPLC", text(0))
        assertFalse(repo.acceptSuggestion(suggestion("thermofisher").id))   // the word was edited away
        assertEquals(SuggestionStatus.STALE, db.suggestions().withStatus(tid, SuggestionStatus.STALE).single().status)
        repo.restoreOriginal(segIds[0])
        assertEquals("we used the hplc from thermofisher", text(0))
    }

    @Test fun acceptAll() = runBlocking {
        assertEquals(2, repo.acceptAll(tid))
        assertEquals("we used the HPLC from Thermo Fisher", text(0))
    }

    @Test fun findAndReplaceWholeWordAndCase() = runBlocking {
        assertEquals(1, repo.replaceAll(tid, "cat", "dog", matchCase = false, wholeWord = true))
        assertEquals("Cats and dog food.", text(1))
        assertEquals(1, repo.replaceAll(tid, "Cats", "Dogs", matchCase = true, wholeWord = true))
        assertEquals("Dogs and dog food.", text(1))
        assertEquals(0, repo.replaceAll(tid, "DOGS", "x", matchCase = true, wholeWord = false))
        // regex metacharacters in the search and "$" / "\\" in the replacement are literal
        assertEquals(1, repo.replaceAll(tid, "food.", "$1 \\x", matchCase = false, wholeWord = false))
        assertEquals("Dogs and dog $1 \\x", text(1))
    }

    @Test fun exportUsesCurrentText() = runBlocking {
        repo.acceptAll(tid)
        val srt = Exporters.srt(repo.exportDocument(tid))
        assertTrue(srt.contains("we used the HPLC from Thermo Fisher"))
        assertTrue(Exporters.exportToBytes(repo.exportDocument(tid), ExportFormat.DOCX).size > 500)
    }

    @Test fun queueRestartsCompletedButResumesInterrupted() = runBlocking {
        repo.queue(tid, null, null, null)
        var t = repo.get(tid)!!
        assertEquals(JobStatus.QUEUED, t.status)
        assertEquals(0, t.nextChunk)                       // completed -> fresh run
        assertTrue(repo.segmentsOf(tid).isEmpty())
        db.transcriptions().update(t.copy(status = JobStatus.RUNNING, chunkPlan = "0-10,10-20", nextChunk = 1, updatedAt = 0))
        repo.markInterrupted(processStartMs = System.currentTimeMillis())
        t = repo.get(tid)!!
        assertEquals(JobStatus.INTERRUPTED, t.status)
        repo.queue(tid, null, null, null)
        assertEquals(1, repo.get(tid)!!.nextChunk)         // resumes
        repo.queue(tid, null, "de", null)
        assertEquals(0, repo.get(tid)!!.nextChunk)         // settings changed -> restart
    }

    @Test fun deleteRemovesAudioAndRows() = runBlocking {
        val path = repo.get(tid)!!.audioPath
        repo.delete(tid)
        assertFalse(File(path).exists())
        assertTrue(repo.segmentsOf(tid).isEmpty())
    }
}
