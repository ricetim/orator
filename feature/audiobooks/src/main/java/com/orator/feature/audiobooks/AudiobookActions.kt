package com.orator.feature.audiobooks

import com.orator.core.model.BookOrigin
import com.orator.core.model.DownloadState

enum class BookAction { PLAY_RESUME, STREAM, DOWNLOAD, CANCEL_DOWNLOAD, REMOVE_DOWNLOAD }

/** Primary + optional secondary action button for a book's detail screen. */
data class BookActions(val primary: BookAction, val secondary: BookAction?)

/**
 * Which action buttons a book shows. PLAY_RESUME's "Play" vs "Resume" label is a UI concern
 * (positionMs > 0), not encoded here. LOCAL books have no download affordance.
 */
fun bookActions(origin: BookOrigin, downloadState: DownloadState): BookActions = when {
    origin == BookOrigin.LOCAL -> BookActions(BookAction.PLAY_RESUME, null)
    downloadState == DownloadState.DOWNLOADED -> BookActions(BookAction.PLAY_RESUME, BookAction.REMOVE_DOWNLOAD)
    downloadState == DownloadState.DOWNLOADING -> BookActions(BookAction.STREAM, BookAction.CANCEL_DOWNLOAD)
    else -> BookActions(BookAction.STREAM, BookAction.DOWNLOAD)   // ABS · NONE
}
