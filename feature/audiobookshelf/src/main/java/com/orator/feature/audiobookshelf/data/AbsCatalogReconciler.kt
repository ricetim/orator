package com.orator.feature.audiobookshelf.data

import com.orator.core.database.BookEntity
import com.orator.core.model.DownloadState

data class ReconcileResult(val upserts: List<BookEntity>, val deletes: List<String>)

/** Pure catalog merge: refresh server-owned metadata, preserve device-owned state, delete stale. */
object AbsCatalogReconciler {
    fun reconcile(existing: List<BookEntity>, incoming: List<BookEntity>): ReconcileResult {
        val old = existing.associateBy { it.id }
        val upserts = incoming.map { fresh ->
            val prev = old[fresh.id] ?: return@map fresh
            fresh.copy(
                positionMs = prev.positionMs,
                lastPlayedAtMs = prev.lastPlayedAtMs,
                speedOverride = prev.speedOverride,
                downloadState = prev.downloadState,
                sourceUri = if (prev.downloadState == DownloadState.DOWNLOADED) prev.sourceUri else fresh.sourceUri,
            )
        }
        val incomingIds = incoming.map { it.id }.toSet()
        val deletes = existing.map { it.id }.filter { it !in incomingIds }
        return ReconcileResult(upserts, deletes)
    }
}
