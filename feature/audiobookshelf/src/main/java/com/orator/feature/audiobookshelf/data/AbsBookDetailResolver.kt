package com.orator.feature.audiobookshelf.data

import com.orator.core.database.BookDao
import com.orator.core.database.ChapterDao
import com.orator.core.model.BookDetailResolver
import com.orator.core.model.BookOrigin

/**
 * Plain class (NOT @Inject — the [detail] function param is supplied by a @Provides in Chunk 6).
 * Lazily fills a mirrored ABS book's playable layout (sourceUri + chapters) the first time it is
 * played; a no-op once populated (or downloaded), so we don't re-hit the server every play.
 */
class AbsBookDetailResolver(
    private val detail: suspend (baseUrl: String, itemId: String) -> AbsBookDetail,
    private val store: AbsCredentialStore,
    private val bookDao: BookDao,
    private val chapterDao: ChapterDao,
) : BookDetailResolver {

    override fun handles(origin: BookOrigin) = origin == BookOrigin.ABS

    override suspend fun ensureDetails(bookId: String) {
        val book = bookDao.getById(bookId) ?: return
        if (book.sourceUri.isNotBlank()) return            // already expanded or downloaded
        val cfg = store.current()?.config ?: return
        val itemId = book.absItemId ?: return
        val d = detail(cfg.baseUrl, itemId)
        chapterDao.replaceForBook(bookId, d.chapters)
        bookDao.upsert(listOf(book.copy(sourceKind = d.sourceKind, sourceUri = d.sourceUri)))
    }
}
