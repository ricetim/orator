package com.orator.core.database

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface HistoryDao {
    @Insert
    suspend fun insert(row: HistoryEntity): Long

    /** Closes the newest open row for [mediaId]; no-op if none (e.g. service restarted). */
    @Query(
        """UPDATE history SET endedAtUtc = :endedAtUtc, completed = :completed
           WHERE id = (SELECT id FROM history WHERE mediaId = :mediaId AND endedAtUtc IS NULL
                       ORDER BY startedAtUtc DESC LIMIT 1)""",
    )
    suspend fun close(mediaId: String, endedAtUtc: Long, completed: Boolean)

    @Query("SELECT * FROM history ORDER BY startedAtUtc DESC LIMIT :limit")
    fun observeRecent(limit: Int): Flow<List<HistoryEntity>>
}
