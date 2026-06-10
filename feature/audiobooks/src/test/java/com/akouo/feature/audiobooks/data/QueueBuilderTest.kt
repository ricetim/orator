package com.akouo.feature.audiobooks.data

import com.akouo.core.database.BookEntity
import com.akouo.core.database.ChapterEntity
import com.akouo.core.database.SourceKind
import com.akouo.core.model.MediaType
import org.junit.Assert.assertEquals
import org.junit.Test

class QueueBuilderTest {

    private fun book(kind: SourceKind) = BookEntity(
        id = "b1",
        title = "Book",
        author = null,
        coverPath = null,
        sourceUri = "uri://book",
        sourceKind = kind,
        durationMs = 60_000,
        positionMs = 0,
        addedAtUtc = 0,
    )

    private fun chapter(index: Int, fileUri: String, startMs: Long, durationMs: Long) = ChapterEntity(
        bookId = "b1",
        chapterIndex = index,
        title = "Ch $index",
        fileUri = fileUri,
        startMs = startMs,
        durationMs = durationMs,
    )

    @Test
    fun `m4b builds a single-item queue starting at the global position`() {
        val chapters = listOf(chapter(0, "uri://book", 0, 30_000), chapter(1, "uri://book", 30_000, 30_000))

        val request = QueueBuilder.build(book(SourceKind.M4B), chapters, startAtMs = 42_000)

        assertEquals(1, request.items.size)
        assertEquals("uri://book", request.items[0].uri)
        assertEquals(AudiobookMediaId.encode("b1", 0), request.items[0].mediaId)
        assertEquals(0, request.startIndex)
        assertEquals(42_000, request.startPositionMs)
        assertEquals(MediaType.AUDIOBOOK, request.mediaType)
    }

    @Test
    fun `mp3 collection builds one item per file and maps the global position`() {
        val chapters = listOf(chapter(0, "uri://f1", 0, 30_000), chapter(1, "uri://f2", 0, 30_000))

        val request = QueueBuilder.build(book(SourceKind.MP3_DIR), chapters, startAtMs = 42_000)

        assertEquals(listOf("uri://f1", "uri://f2"), request.items.map { it.uri })
        assertEquals(
            listOf(AudiobookMediaId.encode("b1", 0), AudiobookMediaId.encode("b1", 1)),
            request.items.map { it.mediaId },
        )
        assertEquals(1, request.startIndex)
        assertEquals(12_000, request.startPositionMs)
    }

    @Test
    fun `chapter titles become item titles for mp3 books`() {
        val chapters = listOf(chapter(0, "uri://f1", 0, 30_000))

        val request = QueueBuilder.build(book(SourceKind.MP3_DIR), chapters, startAtMs = 0)

        assertEquals("Ch 0", request.items[0].title)
    }
}
