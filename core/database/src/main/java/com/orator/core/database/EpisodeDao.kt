package com.orator.core.database

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface EpisodeDao {
    /** Insert new rows only; existing rows are untouched (positions/downloads survive refresh). */
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertIgnore(episodes: List<EpisodeEntity>)

    /**
     * Refresh metadata for an existing row WITHOUT touching positionMs/audioPath/lastPlayedAtMs.
     * durationMs only improves: a 0 from the feed never erases a known value.
     */
    @Query(
        "UPDATE episodes SET title = :title, pubDateUtc = :pubDateUtc, enclosureUrl = :enclosureUrl, " +
            "showNotesHtml = :showNotesHtml, " +
            "durationMs = CASE WHEN :durationMs > 0 THEN :durationMs ELSE durationMs END " +
            "WHERE id = :id",
    )
    suspend fun updateMetadata(
        id: String,
        title: String,
        pubDateUtc: Long,
        enclosureUrl: String,
        showNotesHtml: String?,
        durationMs: Long,
    )

    @Query("SELECT * FROM episodes WHERE podcastId = :podcastId ORDER BY pubDateUtc DESC")
    fun observeForPodcast(podcastId: String): Flow<List<EpisodeEntity>>

    @Query("SELECT * FROM episodes WHERE podcastId = :podcastId ORDER BY pubDateUtc DESC LIMIT :limit")
    suspend fun latestForPodcast(podcastId: String, limit: Int): List<EpisodeEntity>

    @Query("SELECT * FROM episodes WHERE id = :id")
    suspend fun getById(id: String): EpisodeEntity?

    @Query("SELECT * FROM episodes WHERE id = :id")
    fun observeById(id: String): Flow<EpisodeEntity?>

    @Query("UPDATE episodes SET positionMs = :positionMs, lastPlayedAtMs = :lastPlayedAtMs WHERE id = :id")
    suspend fun updateProgress(id: String, positionMs: Long, lastPlayedAtMs: Long)

    /** The duration-backfill rule from the spec, enforced in SQL: never overwrite a known duration. */
    @Query("UPDATE episodes SET durationMs = :durationMs WHERE id = :id AND durationMs = 0")
    suspend fun backfillDuration(id: String, durationMs: Long)

    @Query("UPDATE episodes SET audioPath = :audioPath WHERE id = :id")
    suspend fun updateAudioPath(id: String, audioPath: String?)

    @Query("DELETE FROM episodes WHERE podcastId = :podcastId")
    suspend fun deleteForPodcast(podcastId: String)
}
