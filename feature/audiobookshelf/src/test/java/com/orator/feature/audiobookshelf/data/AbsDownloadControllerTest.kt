package com.orator.feature.audiobookshelf.data

import com.orator.core.model.BookOrigin
import com.orator.core.model.DownloadState
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test

class AbsDownloadControllerTest {
    @Test fun `remove clears chapters, blanks sourceUri, sets NONE`() = runBlocking {
        val books = FakeBookDao().apply {
            upsert(listOf(absBook("abs:1", sourceUri = "content://x", dl = DownloadState.DOWNLOADED)))
        }
        val chapters = FakeChapterDao().apply { replaceForBook("abs:1", listOf(chapter("abs:1", 0, "content://x"))) }
        val controller = AbsDownloadController(
            handlesOrigin = BookOrigin.ABS,
            enqueueFn = {}, cancelFn = {},
            removeFn = { id ->
                // stand-in for AbsFileDownloader.removeDownload (SAF file delete is device-verified)
                chapters.replaceForBook(id, emptyList())
                val b = books.getById(id)!!
                books.upsert(listOf(b.copy(sourceUri = "", downloadState = DownloadState.NONE)))
            },
        )
        controller.remove("abs:1")
        assertEquals("", books.getById("abs:1")!!.sourceUri)
        assertEquals(DownloadState.NONE, books.getById("abs:1")!!.downloadState)
        assertEquals(0, chapters.getForBook("abs:1").size)
    }

    @Test fun `handles only the configured origin`() {
        val c = AbsDownloadController(BookOrigin.ABS, {}, {}, {})
        assertEquals(true, c.handles(BookOrigin.ABS))
        assertEquals(false, c.handles(BookOrigin.LOCAL))
    }
}
