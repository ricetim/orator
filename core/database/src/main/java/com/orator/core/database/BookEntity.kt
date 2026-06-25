package com.orator.core.database

import androidx.room.Entity
import androidx.room.PrimaryKey
import com.orator.core.model.BookOrigin
import com.orator.core.model.DownloadState

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
    /** LOCAL (SAF/device) or ABS (audiobookshelf server). */
    val origin: BookOrigin = BookOrigin.LOCAL,
    /** ABS server this book came from; null for LOCAL. One server in 6a, kept for future multi-server. */
    val serverId: String? = null,
    /** ABS libraryItem id; null for LOCAL. Lets sync (6b) and re-streaming find the server item. */
    val absItemId: String? = null,
    /** Stream-only ABS books are NONE; flips to DOWNLOADED when bytes are local. LOCAL books stay NONE. */
    val downloadState: DownloadState = DownloadState.NONE,
    /** ABS book description/synopsis, filled on first resolve; null for LOCAL or when absent. */
    val description: String? = null,
    /** ABS series display string, e.g. "Foundation #2"; null when the book isn't in a series. */
    val series: String? = null,
)
