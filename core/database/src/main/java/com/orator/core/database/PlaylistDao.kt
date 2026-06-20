package com.orator.core.database

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

/** Lightweight projection for a transactional reorder write (PK + the one column we change). */
data class PlaylistItemPosition(val id: Long, val position: Long)

/** Playlist summary for the list screen: identity + a live item count. */
data class PlaylistSummary(val id: Long, val name: String, val itemCount: Int)

@Dao
interface PlaylistDao {

    @Insert
    suspend fun insertPlaylist(playlist: PlaylistEntity): Long

    @Query("UPDATE playlists SET name = :name WHERE id = :id")
    suspend fun renamePlaylist(id: Long, name: String)

    @Query("DELETE FROM playlists WHERE id = :id")
    suspend fun deletePlaylist(id: Long)

    @Query(
        """
        SELECT p.id AS id, p.name AS name,
               (SELECT COUNT(*) FROM playlist_items i WHERE i.playlistId = p.id) AS itemCount
        FROM playlists p ORDER BY p.createdAtMs DESC, p.id DESC
        """,
    )
    fun observePlaylists(): Flow<List<PlaylistSummary>>

    @Query("SELECT * FROM playlists WHERE id = :id")
    suspend fun getPlaylist(id: Long): PlaylistEntity?

    /** OnConflict IGNORE realizes the unique (playlist, type, mediaId) dedupe. Returns -1 on skip. */
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertItem(item: PlaylistItemEntity): Long

    @Query("SELECT * FROM playlist_items WHERE playlistId = :playlistId ORDER BY position ASC")
    fun observeItems(playlistId: Long): Flow<List<PlaylistItemEntity>>

    @Query("SELECT * FROM playlist_items WHERE playlistId = :playlistId ORDER BY position ASC")
    suspend fun getItems(playlistId: Long): List<PlaylistItemEntity>

    /** Top (currently-playing) item — smallest position. Null when the playlist is empty. */
    @Query("SELECT * FROM playlist_items WHERE playlistId = :playlistId ORDER BY position ASC LIMIT 1")
    suspend fun getTopItem(playlistId: Long): PlaylistItemEntity?

    @Query("SELECT * FROM playlist_items WHERE id = :itemId")
    suspend fun getItem(itemId: Long): PlaylistItemEntity?

    @Query("DELETE FROM playlist_items WHERE id = :itemId")
    suspend fun deleteItem(itemId: Long)

    @Query("SELECT MAX(position) FROM playlist_items WHERE playlistId = :playlistId")
    suspend fun maxPosition(playlistId: Long): Long?

    /** Partial-entity update: rewrites only [PlaylistItemEntity.position] for each id, in one txn. */
    @Update(entity = PlaylistItemEntity::class)
    suspend fun updatePositions(positions: List<PlaylistItemPosition>)
}
