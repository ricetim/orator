package com.orator.feature.playlists.data

import com.orator.core.database.PlaylistEntity
import com.orator.core.database.PlaylistItemEntity
import com.orator.core.model.MediaType

/** Shared test fixtures for the playlists data + playback tests. */

fun playlist(name: String = "Mix", createdAtMs: Long = 0) =
    PlaylistEntity(name = name, createdAtMs = createdAtMs)

fun item(playlistId: Long, type: MediaType, mediaId: String, pos: Long) =
    PlaylistItemEntity(playlistId = playlistId, mediaType = type, mediaId = mediaId, position = pos)
