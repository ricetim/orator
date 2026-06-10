package com.orator.core.database

import androidx.room.Entity
import androidx.room.PrimaryKey

/** How a book arrived in the library; determines how a playback queue is built. */
enum class SourceKind { M4B, MP3_DIR }

/**
 * One audiobook. [positionMs] is the global resume position measured from the start of the
 * whole book (across all files); PositionMapper in feature:audiobooks converts it to a
 * (file, offset) pair for multi-file books.
 */
@Entity(tableName = "books")
data class BookEntity(
    @PrimaryKey val id: String,
    val title: String,
    val author: String?,
    val coverPath: String?,
    val sourceUri: String,
    val sourceKind: SourceKind,
    val durationMs: Long,
    val positionMs: Long = 0,
    val addedAtUtc: Long,
)
