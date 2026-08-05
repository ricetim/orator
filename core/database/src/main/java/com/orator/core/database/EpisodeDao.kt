package com.orator.core.database

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import kotlinx.coroutines.flow.Flow

@Dao
interface EpisodeDao {
    /**
     * Insert new rows only; existing rows are untouched (positions/downloads survive refresh).
     * Returns the rowid per input (in order); -1 marks a row that was ignored as a duplicate —
     * callers use this to detect genuinely-new episodes.
     */
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertIgnore(episodes: List<EpisodeEntity>): List<Long>

    /**
     * Refresh metadata for an existing row WITHOUT touching positionMs/audioPath/
     * lastPlayedAtMs/transcriptPath. durationMs only improves: a 0 from the feed never
     * erases a known value.
     */
    @Query(
        "UPDATE episodes SET title = :title, pubDateUtc = :pubDateUtc, enclosureUrl = :enclosureUrl, " +
            "showNotesHtml = :showNotesHtml, " +
            "transcriptUrl = :transcriptUrl, transcriptType = :transcriptType, " +
            "durationMs = CASE WHEN :durationMs > 0 THEN :durationMs ELSE durationMs END " +
            "WHERE id = :id",
    )
    suspend fun updateMetadata(
        id: String,
        title: String,
        pubDateUtc: Long,
        enclosureUrl: String,
        showNotesHtml: String?,
        transcriptUrl: String?,
        transcriptType: String?,
        durationMs: Long,
    )

    /**
     * Insert new rows, then refresh metadata on all of them, as ONE transaction. The refresh is
     * necessarily a statement per episode; outside a transaction each commits separately, so a
     * 300-episode feed fires ~301 invalidations and every live `episodes` observer re-queries that
     * many times.
     *
     * Atomicity matters here beyond tidiness: a failure between the insert and the caller reading
     * the rowids would leave episodes inserted but never reported as new — and unreportable
     * forever, since the next [insertIgnore] returns -1 for them. Rolling back lets the refresh
     * worker's retry see them again.
     *
     * Do NOT collapse this into @Upsert or ON CONFLICT DO UPDATE. DO UPDATE always reports a
     * changed row, so `changes()` never returns 0, and the -1 duplicate marker that
     * NewEpisodeIds depends on would report every episode as new.
     *
     * Do NOT introduce a withContext/dispatcher switch into this body — Room confines the
     * transaction to its own connection, and hopping dispatchers inside would break it.
     *
     * Returns [insertIgnore]'s rowids, positionally aligned with [episodes].
     */
    @Transaction
    suspend fun insertThenRefresh(episodes: List<EpisodeEntity>): List<Long> {
        val rowIds = insertIgnore(episodes)
        episodes.forEach { e ->
            updateMetadata(
                id = e.id,
                title = e.title,
                pubDateUtc = e.pubDateUtc,
                enclosureUrl = e.enclosureUrl,
                showNotesHtml = e.showNotesHtml,
                transcriptUrl = e.transcriptUrl,
                transcriptType = e.transcriptType,
                durationMs = e.durationMs,
            )
        }
        return rowIds
    }

    @Query("UPDATE episodes SET transcriptPath = :path WHERE id = :id")
    suspend fun updateTranscriptPath(id: String, path: String?)

    /** Newest episode date per podcast — drives the grid tiles' recency sub-line. */
    data class PodcastLatestPub(val podcastId: String, val latestUtc: Long)

    @Query("SELECT podcastId, MAX(pubDateUtc) AS latestUtc FROM episodes GROUP BY podcastId")
    fun observeLatestPubDates(): Flow<List<PodcastLatestPub>>

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
