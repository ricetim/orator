package com.orator.core.database

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface PodcastDao {
    /** Returns -1 when the podcast already exists (subscribe is idempotent). */
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertIgnore(podcast: PodcastEntity): Long

    @Query("SELECT * FROM podcasts ORDER BY title")
    fun observeAll(): Flow<List<PodcastEntity>>

    @Query("SELECT * FROM podcasts WHERE id = :id")
    fun observeById(id: String): Flow<PodcastEntity?>

    @Query("SELECT * FROM podcasts WHERE id = :id")
    suspend fun getById(id: String): PodcastEntity?

    @Query("SELECT * FROM podcasts")
    suspend fun getAll(): List<PodcastEntity>

    @Query(
        "UPDATE podcasts SET title = :title, author = :author, description = :description, " +
            "artworkUrl = :artworkUrl, lastRefreshUtc = :refreshedAtUtc, etag = :etag, " +
            "lastModified = :lastModified WHERE id = :id",
    )
    suspend fun updateFeedMeta(
        id: String,
        title: String,
        author: String?,
        description: String?,
        artworkUrl: String?,
        refreshedAtUtc: Long,
        etag: String?,
        lastModified: String?,
    )

    @Query("UPDATE podcasts SET lastRefreshUtc = :refreshedAtUtc WHERE id = :id")
    suspend fun touchRefresh(id: String, refreshedAtUtc: Long)

    @Query("UPDATE podcasts SET clipIntroMs = :introMs, clipOutroMs = :outroMs WHERE id = :id")
    suspend fun updateClips(id: String, introMs: Long, outroMs: Long)

    @Query("UPDATE podcasts SET speedOverride = :speed WHERE id = :id")
    suspend fun updateSpeedOverride(id: String, speed: Float?)

    @Query("DELETE FROM podcasts WHERE id = :id")
    suspend fun delete(id: String)
}
