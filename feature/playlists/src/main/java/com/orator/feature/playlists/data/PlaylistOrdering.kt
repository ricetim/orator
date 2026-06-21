package com.orator.feature.playlists.data

import com.orator.core.database.PlaylistItemPosition

/**
 * Pure reorder math over a top-first list of playlist item ids. The result is always turned into
 * dense (10, 20, 30, …) positions via [reindex], which the DAO writes in one transaction.
 */
object PlaylistOrdering {
    private const val STEP = 10L

    fun moveToTop(ids: List<Long>, id: Long): List<Long> =
        if (id !in ids) ids else listOf(id) + ids.filterNot { it == id }

    fun move(ids: List<Long>, from: Int, to: Int): List<Long> {
        if (from !in ids.indices || to !in ids.indices || from == to) return ids
        val mutable = ids.toMutableList()
        mutable.add(to, mutable.removeAt(from))
        return mutable
    }

    fun remove(ids: List<Long>, id: Long): List<Long> = ids.filterNot { it == id }

    fun reindex(ids: List<Long>): List<PlaylistItemPosition> =
        ids.mapIndexed { index, id -> PlaylistItemPosition(id, (index + 1) * STEP) }
}
