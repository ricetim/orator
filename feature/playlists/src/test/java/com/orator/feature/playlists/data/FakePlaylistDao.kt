package com.orator.feature.playlists.data

import com.orator.core.database.PlaylistDao
import com.orator.core.database.PlaylistEntity
import com.orator.core.database.PlaylistItemEntity
import com.orator.core.database.PlaylistItemPosition
import com.orator.core.database.PlaylistSummary
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map

/**
 * In-memory [PlaylistDao] double. Models the unique (playlistId, mediaType, mediaId) dedupe,
 * cascade delete, and ordered queries. observe* flows re-emit on every mutation via [changes].
 */
class FakePlaylistDao : PlaylistDao {
    private val playlists = linkedMapOf<Long, PlaylistEntity>()
    private val items = linkedMapOf<Long, PlaylistItemEntity>()
    private var nextPlaylistId = 1L
    private var nextItemId = 1L
    private val changes = MutableStateFlow(0)

    private fun bump() { changes.value++ }
    private fun itemsFor(playlistId: Long) =
        items.values.filter { it.playlistId == playlistId }.sortedBy { it.position }

    override suspend fun insertPlaylist(playlist: PlaylistEntity): Long {
        val id = nextPlaylistId++
        playlists[id] = playlist.copy(id = id)
        bump()
        return id
    }

    override suspend fun renamePlaylist(id: Long, name: String) {
        playlists[id]?.let { playlists[id] = it.copy(name = name); bump() }
    }

    override suspend fun deletePlaylist(id: Long) {
        playlists.remove(id)
        items.values.filter { it.playlistId == id }.forEach { items.remove(it.id) } // cascade
        bump()
    }

    override fun observePlaylists(): Flow<List<PlaylistSummary>> = changes.map {
        playlists.values
            .sortedWith(compareByDescending<PlaylistEntity> { it.createdAtMs }.thenByDescending { it.id })
            .map { p -> PlaylistSummary(p.id, p.name, items.values.count { it.playlistId == p.id }) }
    }

    override suspend fun getPlaylist(id: Long): PlaylistEntity? = playlists[id]

    override suspend fun insertItem(item: PlaylistItemEntity): Long {
        val dup = items.values.any {
            it.playlistId == item.playlistId && it.mediaType == item.mediaType && it.mediaId == item.mediaId
        }
        if (dup) return -1L
        val id = nextItemId++
        items[id] = item.copy(id = id)
        bump()
        return id
    }

    override fun observeItems(playlistId: Long): Flow<List<PlaylistItemEntity>> =
        changes.map { itemsFor(playlistId) }

    override suspend fun getItems(playlistId: Long): List<PlaylistItemEntity> = itemsFor(playlistId)

    override suspend fun getTopItem(playlistId: Long): PlaylistItemEntity? = itemsFor(playlistId).firstOrNull()

    override suspend fun getItem(itemId: Long): PlaylistItemEntity? = items[itemId]

    override suspend fun deleteItem(itemId: Long) { if (items.remove(itemId) != null) bump() }

    override suspend fun maxPosition(playlistId: Long): Long? = itemsFor(playlistId).maxOfOrNull { it.position }

    override suspend fun minPosition(playlistId: Long): Long? = itemsFor(playlistId).minOfOrNull { it.position }

    override suspend fun updatePositions(positions: List<PlaylistItemPosition>) {
        positions.forEach { pos -> items[pos.id]?.let { items[pos.id] = it.copy(position = pos.position) } }
        bump()
    }
}
