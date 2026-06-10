package com.orator.feature.audiobooks.data

import com.orator.core.database.BookEntity
import com.orator.core.database.ChapterEntity
import com.orator.core.database.SourceKind
import com.orator.core.model.MediaType
import org.junit.Assert.assertEquals
import org.junit.Test

class QueueBuilderTest {

    private fun book(kind: SourceKind, speedOverride: Float? = null) = BookEntity(
        id = "b1",
        title = "Book",
        author = null,
        coverPath = null,
        sourceUri = "uri://book",
        sourceKind = kind,
        durationMs = 60_000,
        positionMs = 0,
        addedAtUtc = 0,
        speedOverride = speedOverride,
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
    fun `m4b carries chapter boundaries (excluding zero) and the speed override`() {
        val chapters = listOf(chapter(0, "uri://book", 0, 30_000), chapter(1, "uri://book", 30_000, 30_000))

        val request = QueueBuilder.build(
            book(SourceKind.M4B, speedOverride = 1.3f), chapters, startAtMs = 0,
        )

        // startMs == 0 must NOT appear: "pause at the next boundary" from position 0
        // would otherwise stop instantly.
        assertEquals(listOf(30_000L), request.chapterBoundariesMs)
        assertEquals(1.3f, request.speedOverride)
    }

    @Test
    fun `mp3 collection has no in-item boundaries but keeps the override`() {
        val chapters = listOf(chapter(0, "uri://f1", 0, 30_000), chapter(1, "uri://f2", 0, 30_000))

        val request = QueueBuilder.build(
            book(SourceKind.MP3_DIR, speedOverride = 0.9f), chapters, startAtMs = 0,
        )

        assertEquals(emptyList<Long>(), request.chapterBoundariesMs)
        assertEquals(0.9f, request.speedOverride)
    }

    @Test
    fun `chapter titles become item titles for mp3 books`() {
        val chapters = listOf(chapter(0, "uri://f1", 0, 30_000))

        val request = QueueBuilder.build(book(SourceKind.MP3_DIR), chapters, startAtMs = 0)

        assertEquals("Ch 0", request.items[0].title)
    }
}
