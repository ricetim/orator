package com.orator.feature.playlists.data

import com.orator.core.database.PlaylistDao
import com.orator.core.database.PlaylistEntity
import com.orator.core.database.PlaylistItemEntity
import com.orator.core.database.PlaylistSummary
import com.orator.core.model.MediaRef
import com.orator.core.model.MediaType
import com.orator.core.model.PlaylistItemContent
import com.orator.core.model.PlaylistItemResolver
import kotlinx.coroutines.flow.Flow
import javax.inject.Inject
import javax.inject.Singleton

/** A hydrated playlist row for the UI. */
data class PlaylistItemUi(val itemId: Long, val ref: MediaRef, val content: PlaylistItemContent)

@Singleton
class PlaylistRepository @Inject constructor(
    private val dao: PlaylistDao,
    resolvers: Set<@JvmSuppressWildcards PlaylistItemResolver>,
) {
    private val byType: Map<MediaType, PlaylistItemResolver> = resolvers.associateBy { it.mediaType }

    fun observePlaylists(): Flow<List<PlaylistSummary>> = dao.observePlaylists()
    fun observeItems(playlistId: Long): Flow<List<PlaylistItemEntity>> = dao.observeItems(playlistId)
    suspend fun getPlaylist(id: Long): PlaylistEntity? = dao.getPlaylist(id)

    suspend fun createPlaylist(name: String, nowMs: Long): Long =
        dao.insertPlaylist(PlaylistEntity(name = name.trim(), createdAtMs = nowMs))

    suspend fun renamePlaylist(id: Long, name: String) = dao.renamePlaylist(id, name.trim())
    suspend fun deletePlaylist(id: Long) = dao.deletePlaylist(id)

    /** Append a ref to the bottom. Dedupe is enforced by the DAO's unique index (insert ignored). */
    suspend fun addToBottom(playlistId: Long, ref: MediaRef) {
        val next = (dao.maxPosition(playlistId) ?: 0L) + 10L
        dao.insertItem(
            PlaylistItemEntity(playlistId = playlistId, mediaType = ref.type, mediaId = ref.id, position = next),
        )
    }

    /** Prepend a ref to the top (for NEW_TO_TOP auto-insert). Negative positions are fine — order is
     *  relative and the next reindex normalizes them. Dedupe enforced by the DAO's unique index. */
    suspend fun addAtTop(playlistId: Long, ref: MediaRef) {
        val pos = dao.minPosition(playlistId)?.minus(10) ?: 10L
        dao.insertItem(
            PlaylistItemEntity(playlistId = playlistId, mediaType = ref.type, mediaId = ref.id, position = pos),
        )
    }

    /** Hydrate to UI rows; prune (drop + delete) rows whose entity no longer resolves. */
    suspend fun items(playlistId: Long): List<PlaylistItemUi> = buildList {
        for (row in dao.getItems(playlistId)) {
            val ref = MediaRef(row.mediaType, row.mediaId)
            val content = byType[row.mediaType]?.resolve(ref)
            if (content == null) dao.deleteItem(row.id) else add(PlaylistItemUi(row.id, ref, content))
        }
    }

    suspend fun topRef(playlistId: Long): MediaRef? =
        dao.getTopItem(playlistId)?.let { MediaRef(it.mediaType, it.mediaId) }

    suspend fun removeTop(playlistId: Long) {
        dao.getTopItem(playlistId)?.let { dao.deleteItem(it.id) }
    }

    suspend fun removeItem(itemId: Long) = dao.deleteItem(itemId)

    suspend fun moveToTop(playlistId: Long, itemId: Long) =
        persist(playlistId, PlaylistOrdering.moveToTop(currentIds(playlistId), itemId))

    suspend fun move(playlistId: Long, from: Int, to: Int) =
        persist(playlistId, PlaylistOrdering.move(currentIds(playlistId), from, to))

    private suspend fun currentIds(playlistId: Long): List<Long> =
        dao.getItems(playlistId).map { it.id }

    private suspend fun persist(playlistId: Long, orderedIds: List<Long>) =
        dao.updatePositions(PlaylistOrdering.reindex(orderedIds))
}
