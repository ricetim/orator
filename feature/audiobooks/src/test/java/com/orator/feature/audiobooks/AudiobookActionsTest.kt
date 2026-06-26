package com.orator.feature.audiobooks

import com.orator.core.model.BookOrigin
import com.orator.core.model.DownloadState
import org.junit.Assert.assertEquals
import org.junit.Test

class AudiobookActionsTest {
    @Test fun `local book plays, no download affordance`() =
        assertEquals(BookActions(BookAction.PLAY_RESUME, null),
            bookActions(BookOrigin.LOCAL, DownloadState.NONE))

    @Test fun `abs not downloaded offers stream + download`() =
        assertEquals(BookActions(BookAction.STREAM, BookAction.DOWNLOAD),
            bookActions(BookOrigin.ABS, DownloadState.NONE))

    @Test fun `abs downloading offers stream + cancel`() =
        assertEquals(BookActions(BookAction.STREAM, BookAction.CANCEL_DOWNLOAD),
            bookActions(BookOrigin.ABS, DownloadState.DOWNLOADING))

    @Test fun `abs downloaded offers play + remove`() =
        assertEquals(BookActions(BookAction.PLAY_RESUME, BookAction.REMOVE_DOWNLOAD),
            bookActions(BookOrigin.ABS, DownloadState.DOWNLOADED))
}
