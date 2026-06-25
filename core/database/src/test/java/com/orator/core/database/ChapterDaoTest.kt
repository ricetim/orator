package com.orator.core.database

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ChapterDaoTest {
    private lateinit var db: OratorDatabase
    private lateinit var dao: ChapterDao

    private fun ch(index: Int, title: String) =
        ChapterEntity(bookId = "b1", chapterIndex = index, title = title, fileUri = "u$index", startMs = 0, durationMs = 1000)

    @Before fun setUp() {
        db = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext(), OratorDatabase::class.java,
        ).allowMainThreadQueries().build()
        dao = db.chapterDao()
        // Chapters FK-reference a book; insert the parent first.
        runBlocking {
            db.bookDao().upsert(
                listOf(
                    BookEntity(
                        id = "b1", title = "B", author = null, coverPath = null, sourceUri = "u",
                        sourceKind = SourceKind.SINGLE_FILE, durationMs = 0, addedAtUtc = 0,
                    ),
                ),
            )
        }
    }

    @After fun tearDown() = db.close()

    @Test fun `replaceForBook swaps the whole set`() = runBlocking {
        dao.upsertAll(listOf(ch(0, "old0"), ch(1, "old1")))
        dao.replaceForBook("b1", listOf(ch(0, "only")))
        val rows = dao.getForBook("b1")
        assertEquals(1, rows.size)
        assertEquals("only", rows.single().title)
    }

    @Test fun `replaceForBook with empty list clears the book`() = runBlocking {
        dao.upsertAll(listOf(ch(0, "x")))
        dao.replaceForBook("b1", emptyList())
        assertEquals(0, dao.getForBook("b1").size)
    }
}
