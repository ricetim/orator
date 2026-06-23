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
}
