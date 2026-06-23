package com.orator.core.model

/**
 * Lazily fills in a book's playable detail (sourceUri + chapters) the first time it is played.
 * Each feature contributes one per origin via Hilt @IntoSet. LOCAL books are already complete, so
 * no resolver handles them; ABS books are mirrored as metadata only and resolved on demand.
 */
interface BookDetailResolver {
    fun handles(origin: BookOrigin): Boolean

    /** Idempotent: fetch + persist sourceUri + chapters if not already present; no-op otherwise. */
    suspend fun ensureDetails(bookId: String)
}
