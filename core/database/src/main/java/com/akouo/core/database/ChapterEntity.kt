package com.akouo.core.database

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index

/**
 * A chapter. For M4B books every chapter shares the book's fileUri and startMs is an offset
 * into that file. For MP3_DIR books each chapter is its own file and startMs is 0.
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
