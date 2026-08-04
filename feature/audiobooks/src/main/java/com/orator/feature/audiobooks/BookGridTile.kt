package com.orator.feature.audiobooks

import androidx.compose.runtime.Composable
import com.orator.core.database.BookEntity
import com.orator.core.database.artworkModel
import com.orator.core.designsystem.components.CoverTile
import com.orator.core.designsystem.text.TimeFormats
import com.orator.core.model.DownloadState

/**
 * One book in a 3-column cover grid. Shared by the library screen and the series/author
 * filtered grid so the two can't drift apart in how a book is presented.
 */
@Composable
internal fun BookGridTile(
    book: BookEntity,
    onOpenBook: (String) -> Unit,
    onAddToPlaylist: (String) -> Unit,
) {
    CoverTile(
        artworkModel = book.artworkModel,
        title = book.title,
        // Time-left only once started; no "not started" label (it lives on the detail screen).
        subLine = if (book.positionMs > 0) {
            TimeFormats.timeLeft((book.durationMs - book.positionMs).coerceAtLeast(0))
        } else {
            null
        },
        progress = if (book.durationMs > 0) book.positionMs.toFloat() / book.durationMs else null,
        onClick = { onOpenBook(book.id) },
        onLongClick = { onAddToPlaylist(book.id) },
        downloaded = book.downloadState == DownloadState.DOWNLOADED,
    )
}
