package com.orator.core.database

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import com.orator.core.model.MediaType

/**
 * One pending entry in a playlist — a *pointer* (mediaType + mediaId) to an entity, never a copy.
 * Resume position lives on the target entity (Phase 5a "queue drains" model), so this row carries
 * no progress. [position] orders items; the top (currently-playing) item has the smallest value.
 * The unique index dedupes re-adds of the same entity to the same playlist.
 */
@Entity(
    tableName = "playlist_items",
    foreignKeys = [ForeignKey(
        entity = PlaylistEntity::class,
        parentColumns = ["id"],
        childColumns = ["playlistId"],
        onDelete = ForeignKey.CASCADE,
    )],
    indices = [
        Index("playlistId"),
        Index(value = ["playlistId", "mediaType", "mediaId"], unique = true),
    ],
)
data class PlaylistItemEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val playlistId: Long,
    val mediaType: MediaType,   // PODCAST = episode, AUDIOBOOK = whole book
    val mediaId: String,        // episode.id or book.id (both String PKs)
    val position: Long,
)
