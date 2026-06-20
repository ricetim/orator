package com.orator.core.database

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * How a book's audio is laid out; determines how a playback queue is built.
 * SINGLE_FILE: one file, chapters are internal `chpl` offsets. MULTI_FILE: several files
 * (natural-sorted), chapters are each file's `chpl` flattened contiguously across files.
 */
enum class SourceKind { SINGLE_FILE, MULTI_FILE }

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
    /** Wall-clock of the last position ping; drives cold-start smart rewind. */
    val lastPlayedAtMs: Long = 0,
    /** Per-book speed; null = fall back to per-type/global defaults. */
    val speedOverride: Float? = null,
)
