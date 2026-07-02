package com.orator.feature.audiobookshelf.data

import com.orator.core.database.SourceKind
import com.orator.core.model.BookOrigin
import com.orator.core.model.DownloadState
import org.junit.Assert.assertEquals
import org.junit.Test

class AbsBookMapperTest {
    @Test fun `maps minified item to metadata-only ABS book`() {
        val item = AbsLibraryItem(
            id = "li1",
            media = AbsMedia(
                metadata = AbsMetadata(title = "Dune", authorName = "Herbert"),
                numAudioFiles = 3, duration = 42.5,
            ),
        )
        val b = AbsBookMapper.toBook(item, serverId = "https://abs.example.com", baseUrl = "https://abs.example.com")
        assertEquals("abs:li1", b.id)
        assertEquals("li1", b.absItemId)
        assertEquals(BookOrigin.ABS, b.origin)
        assertEquals("Dune", b.title)
        assertEquals("Herbert", b.author)
        assertEquals("https://abs.example.com/api/items/li1/cover", b.coverPath)
        assertEquals(SourceKind.MULTI_FILE, b.sourceKind)
        assertEquals(42_500, b.durationMs)
        assertEquals("", b.sourceUri)
        assertEquals(DownloadState.NONE, b.downloadState)
    }

    @Test fun `single audio file maps to SINGLE_FILE`() {
        val item = AbsLibraryItem(id = "li2", media = AbsMedia(numAudioFiles = 1))
        assertEquals(SourceKind.SINGLE_FILE, AbsBookMapper.toBook(item, "s", "https://x").sourceKind)
    }

    @Test fun `maps server addedAt into addedAtUtc`() {
        val item = AbsLibraryItem(id = "li1", addedAt = 1_700_000_000_000)
        assertEquals(1_700_000_000_000, AbsBookMapper.toBook(item, "s", "https://x").addedAtUtc)
    }

    @Test fun `absent addedAt becomes the 0L sentinel`() {
        val item = AbsLibraryItem(id = "li1")
        assertEquals(0L, AbsBookMapper.toBook(item, "s", "https://x").addedAtUtc)
    }

    @Test fun `series taken from minified seriesName`() {
        val item = AbsLibraryItem(
            id = "li1",
            media = AbsMedia(metadata = AbsMetadata(seriesName = "Foundation #2")),
        )
        assertEquals("Foundation #2", AbsBookMapper.toBook(item, "s", "https://x").series)
    }

    @Test fun `first series kept when book is in several`() {
        val item = AbsLibraryItem(
            id = "li1",
            media = AbsMedia(metadata = AbsMetadata(seriesName = "Foundation #2, Empire #1")),
        )
        assertEquals("Foundation #2", AbsBookMapper.toBook(item, "s", "https://x").series)
    }

    @Test fun `blank seriesName yields null series`() {
        val item = AbsLibraryItem(id = "li1", media = AbsMedia(metadata = AbsMetadata(seriesName = "  ")))
        assertEquals(null, AbsBookMapper.toBook(item, "s", "https://x").series)
    }
}
