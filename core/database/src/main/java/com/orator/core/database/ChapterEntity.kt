package com.orator.core.database

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index

/**
 * A chapter. [startMs] is an offset within [fileUri]. For SINGLE_FILE books every chapter
 * shares the book's one file. For MULTI_FILE books chapters are each file's `chpl` flattened
 * in order; a file's first chapter starts at 0 so chapters tile the timeline (see
 * ChapterTimeline).
 */
@Entity(
    tableName = "chapters",
    primaryKeys = ["bookId", "chapterIndex"],
    foreignKeys = [
        ForeignKey(
            entity = BookEntity::class,
            parentColumns = ["id"],
            childColumns = ["bookId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index("bookId")],
)
data class ChapterEntity(
    val bookId: String,
    val chapterIndex: Int,
    val title: String,
    val fileUri: String,
    val startMs: Long,
    val durationMs: Long,
)
