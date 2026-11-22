package com.offlinetranscriber.data

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(tableName = "keywords", indices = [Index(value = ["phraseKey"], unique = true), Index("category")])
data class KeywordEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val phrase: String,
    /** Case/space/punctuation-insensitive key used to detect duplicates. */
    val phraseKey: String,
    /** Alternative spellings, one per line. */
    val alternatives: String = "",
    val pronunciation: String? = null,
    val category: String,
    val notes: String? = null,
    val priority: Boolean = false,
    val hitCount: Int = 0,
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis(),
) {
    val alternativeList: List<String> get() = alternatives.lines().map { it.trim() }.filter { it.isNotEmpty() }
}

@Entity(tableName = "categories")
data class CategoryEntity(
    @PrimaryKey val name: String,
    val builtIn: Boolean,
    val createdAt: Long = System.currentTimeMillis(),
)

@Entity(tableName = "models")
data class ModelEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val displayName: String,
    /** Absolute path of a model copied into app storage, or null when used in place via [contentUri]. */
    val filePath: String?,
    /** Persisted SAF document URI for models registered without copying. */
    val contentUri: String?,
    val sizeBytes: Long,
    val format: String,
    val sizeClass: String?,
    val quantization: String?,
    val multilingual: Boolean?,
    val compatible: Boolean,
    val compatibilityMessage: String,
    /** 1 = loaded successfully by the engine, -1 = load failed, 0 = not yet verified. */
    val verified: Int = 0,
    val verifyMessage: String? = null,
    val notes: String? = null,
    val addedAt: Long = System.currentTimeMillis(),
)

object JobStatus {
    const val QUEUED = "QUEUED"
    const val PREPARING = "PREPARING"
    const val RUNNING = "RUNNING"
    const val INTERRUPTED = "INTERRUPTED"
    const val COMPLETED = "COMPLETED"
    const val FAILED = "FAILED"
    const val CANCELLED = "CANCELLED"
    /** Recording saved, transcription not yet requested ("record now, transcribe later"). */
    const val NOT_STARTED = "NOT_STARTED"

    val ACTIVE = setOf(QUEUED, PREPARING, RUNNING)
    val RESUMABLE = setOf(INTERRUPTED, FAILED, CANCELLED, NOT_STARTED)
}

@Entity(tableName = "transcriptions", indices = [Index("createdAt")])
data class TranscriptionEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val title: String,
    /** Original audio kept in app storage (link between audio and transcript). */
    val audioPath: String,
    val audioDurationMs: Long = 0,
    /** 16 kHz mono WAV used for inference (may equal [audioPath] for app recordings). */
    val pcmPath: String? = null,
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis(),
    val modelId: Long? = null,
    val modelName: String? = null,
    val languageRequested: String = "auto",
    val languageDetected: String? = null,
    val status: String = JobStatus.QUEUED,
    val progress: Float = 0f,
    /** Chunk boundaries, see ChunkPlanner.encode. */
    val chunkPlan: String? = null,
    val nextChunk: Int = 0,
    val previousText: String? = null,
    /** Comma-separated keyword ids already recognised in this recording. */
    val seenTermIds: String = "",
    val error: String? = null,
    val processingMs: Long = 0,
    /** Human-readable description of how vocabulary assistance was applied. */
    val vocabularyNote: String? = null,
    /** Category filter used for vocabulary (empty = all). Newline separated. */
    val vocabularyCategories: String = "",
)

@Entity(
    tableName = "segments",
    foreignKeys = [ForeignKey(entity = TranscriptionEntity::class, parentColumns = ["id"], childColumns = ["transcriptionId"], onDelete = ForeignKey.CASCADE)],
    indices = [Index(value = ["transcriptionId", "position"])],
)
data class SegmentEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val transcriptionId: Long,
    val position: Int,
    val startMs: Long,
    val endMs: Long,
    /** Exactly what the engine produced (kept when "Preserve original" is on). */
    val originalText: String?,
    /** Current text after edits and accepted corrections. */
    val text: String,
)

object SuggestionStatus {
    const val PENDING = "PENDING"
    const val ACCEPTED = "ACCEPTED"
    const val REJECTED = "REJECTED"
    const val AUTO_APPLIED = "AUTO_APPLIED"
    const val STALE = "STALE"
}

@Entity(
    tableName = "suggestions",
    foreignKeys = [
        ForeignKey(entity = TranscriptionEntity::class, parentColumns = ["id"], childColumns = ["transcriptionId"], onDelete = ForeignKey.CASCADE),
        ForeignKey(entity = SegmentEntity::class, parentColumns = ["id"], childColumns = ["segmentId"], onDelete = ForeignKey.CASCADE),
    ],
    indices = [Index("transcriptionId"), Index("segmentId")],
)
data class SuggestionEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val transcriptionId: Long,
    val segmentId: Long,
    val startOffset: Int,
    val endOffset: Int,
    val original: String,
    val replacement: String,
    val termId: Long,
    val confidence: Double,
    val kind: String,
    val status: String = SuggestionStatus.PENDING,
)

@Entity(tableName = "settings")
data class SettingEntity(@PrimaryKey val key: String, val value: String)
