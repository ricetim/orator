package com.orator.core.database

import androidx.room.Database
import androidx.room.RoomDatabase

/**
 * exportSchema is off while the schema is pre-release and allowed to change freely.
 * It MUST be enabled (with committed schema files + migration tests) before the first
 * public release — tracked under roadmap Phase 9.
 */
@Database(
    entities = [
        BookEntity::class, ChapterEntity::class, BookmarkEntity::class, HistoryEntity::class,
        PodcastEntity::class, EpisodeEntity::class,
    ],
    version = 3,
    exportSchema = false,
)
abstract class OratorDatabase : RoomDatabase() {
    abstract fun bookDao(): BookDao
    abstract fun chapterDao(): ChapterDao
    abstract fun bookmarkDao(): BookmarkDao
    abstract fun historyDao(): HistoryDao
    abstract fun podcastDao(): PodcastDao
    abstract fun episodeDao(): EpisodeDao
}
