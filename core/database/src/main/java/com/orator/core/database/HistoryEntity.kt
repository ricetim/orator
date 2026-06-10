package com.orator.core.database

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * One listening session of one queue item. [endedAtUtc] == null means the session is either
 * still running or was interrupted (process killed) — the UI treats both as "no end time";
 * we never invent one. [mediaType] is the MediaType enum name, nullable because items not
 * built by MediaItemFactory carry none.
 */
@Entity(tableName = "history")
data class HistoryEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val mediaId: String,
    val title: String,
    val mediaType: String?,
    val startedAtUtc: Long,
    val endedAtUtc: Long? = null,
    val completed: Boolean = false,
)
