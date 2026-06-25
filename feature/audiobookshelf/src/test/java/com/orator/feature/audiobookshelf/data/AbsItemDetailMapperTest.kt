package com.orator.feature.audiobookshelf.data

import com.orator.core.database.SourceKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class AbsItemDetailMapperTest {
    @Test fun `single audio file uses internal chapter offsets`() {
        val item = AbsLibraryItem("li1", AbsMedia(
            audioFiles = listOf(AbsAudioFile(ino = "100", index = 1, duration = 60.0)),
            chapters = listOf(
                AbsChapter(start = 0.0, end = 30.0, title = "Ch1"),
                AbsChapter(start = 30.0, end = 60.0, title = "Ch2"),
            ),
        ))
        val d = AbsItemDetailMapper.map(item, baseUrl = "https://abs.example.com")
        assertEquals(SourceKind.SINGLE_FILE, d.sourceKind)
        assertEquals("https://abs.example.com/api/items/li1/file/100", d.sourceUri)
        assertEquals(2, d.chapters.size)
        assertEquals("Ch1", d.chapters[0].title)
        assertEquals("https://abs.example.com/api/items/li1/file/100", d.chapters[0].fileUri)
        assertEquals(0, d.chapters[0].startMs)
        assertEquals(30_000, d.chapters[1].startMs)
    }

    @Test fun `multiple audio files become one chapter per track`() {
        val item = AbsLibraryItem("li2", AbsMedia(
            audioFiles = listOf(
                AbsAudioFile(ino = "1", index = 1, duration = 60.0),
                AbsAudioFile(ino = "2", index = 2, duration = 90.0),
            ),
        ))
        val d = AbsItemDetailMapper.map(item, baseUrl = "https://abs.example.com")
        assertEquals(SourceKind.MULTI_FILE, d.sourceKind)
        assertEquals("https://abs.example.com/api/items/li2/file/1", d.sourceUri)
        assertEquals(2, d.chapters.size)
        assertEquals("https://abs.example.com/api/items/li2/file/1", d.chapters[0].fileUri)
        assertEquals(0, d.chapters[0].startMs)
        assertEquals(60_000, d.chapters[0].durationMs)
        assertEquals("https://abs.example.com/api/items/li2/file/2", d.chapters[1].fileUri)
        assertEquals(0, d.chapters[1].startMs)
    }

    private fun item(md: AbsMetadata) = AbsLibraryItem(
        "li1",
        AbsMedia(
            metadata = md,
            audioFiles = listOf(AbsAudioFile("100", 1, 60.0)),
            chapters = listOf(AbsChapter(start = 0.0, end = 60.0, title = "Ch1")),
        ),
    )

    @Test fun `series name and sequence join as name hash seq`() {
        val d = AbsItemDetailMapper.map(
            item(AbsMetadata(description = "blurb", series = listOf(AbsSeries("Foundation", "2")))),
            "https://abs.example.com",
        )
        assertEquals("blurb", d.description)
        assertEquals("Foundation #2", d.series)
    }

    @Test fun `series without sequence is just the name`() {
        val d = AbsItemDetailMapper.map(
            item(AbsMetadata(series = listOf(AbsSeries("Foundation", null)))), "https://x",
        )
        assertEquals("Foundation", d.series)
    }

    @Test fun `missing description and series map to null`() {
        val d = AbsItemDetailMapper.map(item(AbsMetadata()), "https://x")
        assertNull(d.description)
        assertNull(d.series)
    }
}
