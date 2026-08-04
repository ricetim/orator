package com.orator.core.database

import com.orator.core.model.BookOrigin
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.io.File

class BookArtworkTest {

    private fun book(origin: BookOrigin, coverPath: String?) = BookEntity(
        id = "b1",
        title = "A Book",
        author = null,
        coverPath = coverPath,
        sourceUri = "content://x",
        sourceKind = SourceKind.SINGLE_FILE,
        durationMs = 1,
        addedAtUtc = 0,
        origin = origin,
    )

    @Test
    fun `ABS cover stays a URL string`() {
        val model = book(BookOrigin.ABS, "https://server/api/items/abc/cover").artworkModel
        assertEquals("https://server/api/items/abc/cover", model)
    }

    @Test
    fun `local cover becomes a File`() {
        val model = book(BookOrigin.LOCAL, "/data/covers/b1.jpg").artworkModel
        assertEquals(File("/data/covers/b1.jpg"), model)
    }

    @Test
    fun `missing cover is null for either origin`() {
        assertNull(book(BookOrigin.ABS, null).artworkModel)
        assertNull(book(BookOrigin.LOCAL, null).artworkModel)
    }
}
