package com.orator.feature.audiobookshelf.data

import com.orator.core.database.BookEntity
import com.orator.core.model.DownloadState

data class ReconcileResult(val upserts: List<BookEntity>, val deletes: List<String>)

/** Pure catalog merge: refresh server-owned metadata, preserve device-owned state, delete stale. */
object AbsCatalogReconciler {
    fun reconcile(
        existing: List<BookEntity>,
        incoming: List<BookEntity>,
        now: Long = System.currentTimeMillis(),
    ): ReconcileResult {
        val old = existing.associateBy { it.id }
        val upserts = incoming.map { fresh ->
            val serverAdded = fresh.addedAtUtc.takeIf { it > 0L }
            val prev = old[fresh.id] ?: return@map fresh.copy(addedAtUtc = serverAdded ?: now)
            fresh.copy(
                positionMs = prev.positionMs,
                lastPlayedAtMs = prev.lastPlayedAtMs,
                speedOverride = prev.speedOverride,
                downloadState = prev.downloadState,
                addedAtUtc = serverAdded ?: prev.addedAtUtc,   // prefer server; else keep first-seen
                sourceUri = if (prev.downloadState == DownloadState.DOWNLOADED) prev.sourceUri else fresh.sourceUri,
            )
        }
        val incomingIds = incoming.map { it.id }.toSet()
        val deletes = existing.map { it.id }.filter { it !in incomingIds }
        return ReconcileResult(upserts, deletes)
    }
}
