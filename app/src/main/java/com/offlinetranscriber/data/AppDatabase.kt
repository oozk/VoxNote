package com.offlinetranscriber.data

import android.content.Context
import androidx.room.AutoMigration
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

@Database(
    entities = [
        KeywordEntity::class, CategoryEntity::class, ModelEntity::class, TranscriptionEntity::class,
        SegmentEntity::class, SuggestionEntity::class, SettingEntity::class,
        WordReplacementEntity::class, ModeEntity::class,
    ],
    version = 2,
    exportSchema = true,
    autoMigrations = [AutoMigration(from = 1, to = 2)],
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun keywords(): KeywordDao
    abstract fun categories(): CategoryDao
    abstract fun models(): ModelDao
    abstract fun transcriptions(): TranscriptionDao
    abstract fun segments(): SegmentDao
    abstract fun suggestions(): SuggestionDao
    abstract fun settings(): SettingDao
    abstract fun replacements(): WordReplacementDao
    abstract fun modes(): ModeDao

    companion object {
        fun create(context: Context, inMemory: Boolean = false): AppDatabase {
            val b = if (inMemory) Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            else Room.databaseBuilder(context, AppDatabase::class.java, "offline_transcriber.db")
            return b.build()
        }
    }
}
