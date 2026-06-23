package com.orator.feature.audiobookshelf.data

import com.orator.core.model.BookDownloadController
import com.orator.core.model.BookOrigin

/** Bridges the core BookDownloadController seam to the manager + downloader (wired by Hilt in Chunk 6). */
class AbsDownloadController(
    private val handlesOrigin: BookOrigin,
    private val enqueueFn: (String) -> Unit,
    private val cancelFn: (String) -> Unit,
    private val removeFn: suspend (String) -> Unit,
) : BookDownloadController {
    override fun handles(origin: BookOrigin) = origin == handlesOrigin
    override fun enqueue(bookId: String) = enqueueFn(bookId)
    override fun cancel(bookId: String) = cancelFn(bookId)
    override suspend fun remove(bookId: String) = removeFn(bookId)
}
