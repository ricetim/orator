package com.orator.core.database

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class OratorDatabaseTest {

    private lateinit var db: OratorDatabase

    @Before
    fun setUp() {
        db = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext(),
            OratorDatabase::class.java,
        ).allowMainThreadQueries().build()
    }

    @After
    fun tearDown() {
        db.close()
    }

    private fun book(id: String = "b1", positionMs: Long = 0) = BookEntity(
        id = id,
        title = "A Book",
        author = "An Author",
        coverPath = null,
        sourceUri = "content://tree/doc/$id",
        sourceKind = SourceKind.M4B,
        durationMs = 100_000,
        positionMs = positionMs,
        addedAtUtc = 1_000,
    )

    private fun chapter(bookId: String, index: Int) = ChapterEntity(
        bookId = bookId,
        chapterIndex = index,
        title = "Chapter $index",
        fileUri = "content://tree/doc/$bookId",
        startMs = index * 10_000L,
        durationMs = 10_000,
    )

    @Test
    fun `book roundtrip and position update`() = runBlocking {
        db.bookDao().upsert(listOf(book()))

        db.bookDao().updateProgress("b1", 42_000, lastPlayedAtMs = 777)

        val loaded = db.bookDao().getById("b1")!!
        assertEquals(42_000, loaded.positionMs)
        assertEquals(777, loaded.lastPlayedAtMs)
        assertEquals("A Book", loaded.title)
        assertEquals(SourceKind.M4B, loaded.sourceKind)
    }

    @Test
    fun `observeAll emits books sorted by title`() = runBlocking {
        db.bookDao().upsert(listOf(book("b1").copy(title = "Zebra"), book("b2").copy(title = "Aardvark")))

        val titles = db.bookDao().observeAll().first().map { it.title }

        assertEquals(listOf("Aardvark", "Zebra"), titles)
    }

    @Test
    fun `chapters come back ordered by index`() = runBlocking {
        db.bookDao().upsert(listOf(book()))
        db.chapterDao().upsertAll(listOf(chapter("b1", 2), chapter("b1", 0), chapter("b1", 1)))

        val indices = db.chapterDao().getForBook("b1").map { it.chapterIndex }

        assertEquals(listOf(0, 1, 2), indices)
    }

    @Test
    fun `deleting a book cascades to chapters and bookmarks`() = runBlocking {
        db.bookDao().upsert(listOf(book()))
        db.chapterDao().upsertAll(listOf(chapter("b1", 0)))
        db.bookmarkDao().insert(
            BookmarkEntity(bookId = "b1", positionMs = 5_000, note = "hi", createdAtUtc = 1_000),
        )

        db.bookDao().deleteByIds(listOf("b1"))

        assertTrue(db.chapterDao().getForBook("b1").isEmpty())
        assertTrue(db.bookmarkDao().observeForBook("b1").first().isEmpty())
    }

    @Test
    fun `bookmarks observe newest first`() = runBlocking {
        db.bookDao().upsert(listOf(book()))
        db.bookmarkDao().insert(BookmarkEntity(bookId = "b1", positionMs = 1_000, note = null, createdAtUtc = 1))
        db.bookmarkDao().insert(BookmarkEntity(bookId = "b1", positionMs = 2_000, note = null, createdAtUtc = 2))

        val positions = db.bookmarkDao().observeForBook("b1").first().map { it.positionMs }

        assertEquals(listOf(2_000L, 1_000L), positions)
    }
}
