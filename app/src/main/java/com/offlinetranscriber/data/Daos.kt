package com.offlinetranscriber.data

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Update
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

data class CategoryCount(val category: String, val count: Int)

@Dao
interface KeywordDao {
    @Query("SELECT * FROM keywords ORDER BY category COLLATE NOCASE, phrase COLLATE NOCASE")
    fun observeAll(): Flow<List<KeywordEntity>>

    @Query(
        """SELECT * FROM keywords WHERE phrase LIKE '%' || :q || '%' OR alternatives LIKE '%' || :q || '%'
           OR notes LIKE '%' || :q || '%' ORDER BY category COLLATE NOCASE, phrase COLLATE NOCASE"""
    )
    fun search(q: String): Flow<List<KeywordEntity>>

    @Query("SELECT * FROM keywords")
    suspend fun all(): List<KeywordEntity>

    @Query("SELECT * FROM keywords WHERE category IN (:categories)")
    suspend fun inCategories(categories: List<String>): List<KeywordEntity>

    @Query("SELECT COUNT(*) FROM keywords")
    fun observeCount(): Flow<Int>

    @Query("SELECT category, COUNT(*) AS count FROM keywords GROUP BY category")
    fun observeCategoryCounts(): Flow<List<CategoryCount>>

    @Query("SELECT * FROM keywords WHERE id = :id")
    suspend fun get(id: Long): KeywordEntity?

    @Query("SELECT * FROM keywords WHERE phraseKey = :key LIMIT 1")
    suspend fun byKey(key: String): KeywordEntity?

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insert(k: KeywordEntity): Long

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertAll(list: List<KeywordEntity>): List<Long>

    @Update
    suspend fun update(k: KeywordEntity)

    @Update
    suspend fun updateAll(list: List<KeywordEntity>)

    @Query("DELETE FROM keywords WHERE id IN (:ids)")
    suspend fun deleteIds(ids: List<Long>)

    @Query("UPDATE keywords SET category = :to WHERE category = :from")
    suspend fun moveCategory(from: String, to: String)

    @Query("SELECT * FROM keywords WHERE priority = 1")
    suspend fun priority(): List<KeywordEntity>

    @Query("UPDATE keywords SET hitCount = hitCount + 1 WHERE id IN (:ids)")
    suspend fun incrementHits(ids: List<Long>)

    @Query("DELETE FROM keywords")
    suspend fun deleteAll()
}

@Dao
interface CategoryDao {
    @Query("SELECT * FROM categories ORDER BY builtIn DESC, name COLLATE NOCASE")
    fun observeAll(): Flow<List<CategoryEntity>>

    @Query("SELECT * FROM categories")
    suspend fun all(): List<CategoryEntity>

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insert(c: CategoryEntity)

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertAll(c: List<CategoryEntity>)

    @Query("DELETE FROM categories WHERE name = :name AND builtIn = 0")
    suspend fun deleteCustom(name: String)

    @Query("DELETE FROM categories")
    suspend fun deleteAll()
}

@Dao
interface ModelDao {
    @Query("SELECT * FROM models ORDER BY addedAt DESC")
    fun observeAll(): Flow<List<ModelEntity>>

    @Query("SELECT * FROM models ORDER BY addedAt DESC")
    suspend fun all(): List<ModelEntity>

    @Query("SELECT * FROM models WHERE id = :id")
    suspend fun get(id: Long): ModelEntity?

    @Insert
    suspend fun insert(m: ModelEntity): Long

    @Update
    suspend fun update(m: ModelEntity)

    @Delete
    suspend fun delete(m: ModelEntity)

    @Query("DELETE FROM models")
    suspend fun deleteAll()
}

@Dao
interface TranscriptionDao {
    @Query("SELECT * FROM transcriptions ORDER BY createdAt DESC")
    fun observeAll(): Flow<List<TranscriptionEntity>>

    @Query("SELECT * FROM transcriptions WHERE id = :id")
    fun observe(id: Long): Flow<TranscriptionEntity?>

    @Query("SELECT * FROM transcriptions WHERE id = :id")
    suspend fun get(id: Long): TranscriptionEntity?

    @Query("SELECT * FROM transcriptions WHERE status IN (:statuses)")
    suspend fun withStatus(statuses: List<String>): List<TranscriptionEntity>

    @Insert
    suspend fun insert(t: TranscriptionEntity): Long

    @Update
    suspend fun update(t: TranscriptionEntity)

    @Query("DELETE FROM transcriptions WHERE id = :id")
    suspend fun delete(id: Long)

    @Query("DELETE FROM transcriptions")
    suspend fun deleteAll()

    /** First segment text of each transcription, used for history previews. */
    @Query(
        """SELECT transcriptionId AS id, GROUP_CONCAT(text, ' ') AS preview FROM
           (SELECT transcriptionId, text FROM segments WHERE position < 6 ORDER BY transcriptionId, position)
           GROUP BY transcriptionId"""
    )
    fun observePreviews(): Flow<List<TranscriptPreview>>
}

data class TranscriptPreview(val id: Long, val preview: String?)

@Dao
interface SegmentDao {
    @Query("SELECT * FROM segments WHERE transcriptionId = :tid ORDER BY position")
    fun observeFor(tid: Long): Flow<List<SegmentEntity>>

    @Query("SELECT * FROM segments WHERE transcriptionId = :tid ORDER BY position")
    suspend fun forTranscription(tid: Long): List<SegmentEntity>

    @Query("SELECT COALESCE(MAX(position), -1) FROM segments WHERE transcriptionId = :tid")
    suspend fun maxPosition(tid: Long): Int

    @Query("SELECT * FROM segments WHERE id = :id")
    suspend fun get(id: Long): SegmentEntity?

    @Insert
    suspend fun insertAll(list: List<SegmentEntity>): List<Long>

    @Update
    suspend fun update(s: SegmentEntity)

    @Update
    suspend fun updateAll(list: List<SegmentEntity>)

    @Query("DELETE FROM segments WHERE transcriptionId = :tid AND startMs >= :fromMs")
    suspend fun deleteFrom(tid: Long, fromMs: Long)

    @Query("DELETE FROM segments WHERE transcriptionId = :tid")
    suspend fun deleteFor(tid: Long)

    @Query("UPDATE segments SET speaker = :to WHERE transcriptionId = :tid AND speaker = :from")
    suspend fun mergeSpeaker(tid: Long, from: Int, to: Int)
}

@Dao
interface SuggestionDao {
    @Query("SELECT * FROM suggestions WHERE transcriptionId = :tid ORDER BY segmentId, startOffset")
    fun observeFor(tid: Long): Flow<List<SuggestionEntity>>

    @Query("SELECT * FROM suggestions WHERE transcriptionId = :tid AND status = :status")
    suspend fun withStatus(tid: Long, status: String): List<SuggestionEntity>

    @Query("SELECT * FROM suggestions WHERE id = :id")
    suspend fun get(id: Long): SuggestionEntity?

    @Insert
    suspend fun insertAll(list: List<SuggestionEntity>)

    @Update
    suspend fun update(s: SuggestionEntity)

    @Query("DELETE FROM suggestions WHERE transcriptionId = :tid")
    suspend fun deleteFor(tid: Long)
}

@Dao
interface SettingDao {
    @Query("SELECT * FROM settings")
    fun observeAll(): Flow<List<SettingEntity>>

    @Query("SELECT * FROM settings")
    suspend fun all(): List<SettingEntity>

    @Upsert
    suspend fun put(s: SettingEntity)

    @Query("DELETE FROM settings")
    suspend fun deleteAll()
}

@Dao
interface WordReplacementDao {
    @Query("SELECT * FROM word_replacements ORDER BY find COLLATE NOCASE")
    fun observeAll(): Flow<List<WordReplacementEntity>>

    @Query("SELECT * FROM word_replacements WHERE enabled = 1")
    suspend fun enabled(): List<WordReplacementEntity>

    @Insert
    suspend fun insert(r: WordReplacementEntity): Long

    @Update
    suspend fun update(r: WordReplacementEntity)

    @Query("DELETE FROM word_replacements WHERE id = :id")
    suspend fun delete(id: Long)
}

@Dao
interface ModeDao {
    @Query("SELECT * FROM modes ORDER BY name COLLATE NOCASE")
    fun observeAll(): Flow<List<ModeEntity>>

    @Insert
    suspend fun insert(m: ModeEntity): Long

    @Query("DELETE FROM modes WHERE id = :id")
    suspend fun delete(id: Long)
}
