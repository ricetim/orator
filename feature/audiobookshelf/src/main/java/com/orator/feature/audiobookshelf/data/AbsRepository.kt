package com.orator.feature.audiobookshelf.data

import com.orator.core.database.BookDao
import com.orator.core.model.BookOrigin

/**
 * Mirrors the ABS catalog into the shared books table (origin=ABS) and tears it down on logout.
 * Provider-only (no @Inject): the [deleteFiles] function seam is wired to AbsFileDownloader by the
 * Hilt provider in Chunk 5; it is a no-op here so sync/logout work before downloads exist.
 */
class AbsRepository(
    private val source: AbsCatalogSource,
    private val store: AbsCredentialStore,
    private val bookDao: BookDao,
    private val deleteFiles: suspend (String) -> Unit = {},
) {
    /** One reconcile pass; no-op when disconnected. */
    suspend fun sync() {
        val cfg = store.current()?.config ?: return
        val incoming = source.libraries(cfg.baseUrl, cfg.token)
            .filter { it.mediaType == null || it.mediaType == "book" }
            .flatMap { lib -> source.items(cfg.baseUrl, lib.id, cfg.token) }
            .map { AbsBookMapper.toBook(it, cfg.serverId, cfg.baseUrl) }
        val existing = bookDao.getByOrigin(BookOrigin.ABS)
        val result = AbsCatalogReconciler.reconcile(existing, incoming)
        bookDao.upsert(result.upserts)
        if (result.deletes.isNotEmpty()) {
            result.deletes.forEach { deleteFiles(it) }      // no-op until Chunk 5 wires it
            bookDao.deleteByIds(result.deletes)
        }
    }

    suspend fun logout() {
        val ids = bookDao.getIdsByOrigin(BookOrigin.ABS)
        if (ids.isNotEmpty()) {
            ids.forEach { deleteFiles(it) }
            bookDao.deleteByIds(ids)
        }
        store.clear()
    }
}
