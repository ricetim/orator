package com.orator.core.model

/**
 * Offline-download actions for a book, contributed per origin via Hilt @IntoSet so the audiobooks
 * list can offer download/remove for ABS books without importing feature:audiobookshelf.
 */
interface BookDownloadController {
    fun handles(origin: BookOrigin): Boolean
    fun enqueue(bookId: String)
    fun cancel(bookId: String)
    suspend fun remove(bookId: String)
}
