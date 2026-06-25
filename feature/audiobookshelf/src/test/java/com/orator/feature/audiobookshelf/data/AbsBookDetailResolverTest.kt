package com.orator.feature.audiobookshelf.data

import com.orator.core.model.BookOrigin
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

class AbsBookDetailResolverTest {
    @Test fun `handles only ABS`() {
        val r = AbsBookDetailResolver(
            detail = { _, _ -> error("unused") },
            store = connectedStore(), bookDao = FakeBookDao(), chapterDao = FakeChapterDao(),
        )
        assertEquals(true, r.handles(BookOrigin.ABS))
        assertEquals(false, r.handles(BookOrigin.LOCAL))
    }

    @Test fun `ensureDetails fills sourceUri and chapters when blank, then is a no-op`() = runBlocking {
        val books = FakeBookDao().apply { upsert(listOf(absBook("abs:li1", sourceUri = ""))) }
        val chapters = FakeChapterDao()
        var calls = 0
        val r = AbsBookDetailResolver(
            detail = { _, _ ->
                calls++
                AbsItemDetailMapper.map(
                    AbsLibraryItem(
                        "li1",
                        AbsMedia(
                            audioFiles = listOf(AbsAudioFile("100", 1, 60.0)),
                            chapters = listOf(AbsChapter(start = 0.0, end = 60.0, title = "Ch1")),
                        ),
                    ),
                    "https://abs.example.com",
                )
            },
            store = connectedStore(), bookDao = books, chapterDao = chapters,
        )
        r.ensureDetails("abs:li1")
        assertNotEquals("", books.getById("abs:li1")!!.sourceUri)
        assertEquals(1, chapters.getForBook("abs:li1").size)

        r.ensureDetails("abs:li1")       // already populated
        assertEquals(1, calls)            // network not hit again
    }

    @Test fun `ensureDetails persists description and series`() = runBlocking {
        val books = FakeBookDao().apply { upsert(listOf(absBook("abs:li1", sourceUri = ""))) }
        val r = AbsBookDetailResolver(
            detail = { _, _ ->
                AbsItemDetailMapper.map(
                    AbsLibraryItem("li1", AbsMedia(
                        metadata = AbsMetadata(
                            description = "blurb", series = listOf(AbsSeries("Foundation", "2")),
                        ),
                        audioFiles = listOf(AbsAudioFile("100", 1, 60.0)),
                        chapters = listOf(AbsChapter(start = 0.0, end = 60.0, title = "Ch1")),
                    )),
                    "https://abs.example.com",
                )
            },
            store = connectedStore(), bookDao = books, chapterDao = FakeChapterDao(),
        )
        r.ensureDetails("abs:li1")
        val row = books.getById("abs:li1")!!
        assertEquals("blurb", row.description)
        assertEquals("Foundation #2", row.series)
    }
}
