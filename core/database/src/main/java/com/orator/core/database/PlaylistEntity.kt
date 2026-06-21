package com.orator.core.database

import androidx.room.Entity
import androidx.room.PrimaryKey

/** A user-named playlist. Items live in [PlaylistItemEntity]; this row carries only identity. */
@Entity(tableName = "playlists")
data class PlaylistEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val createdAtMs: Long,
)
