package com.orator.core.database

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.orator.core.model.BookOrigin
import com.orator.core.model.DownloadState
import kotlinx.coroutines.flow.first
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
class BookDaoOriginTest {
    private lateinit var db: OratorDatabase
    private lateinit var dao: BookDao

    private fun book(id: String, origin: BookOrigin) = BookEntity(
        id = id, title = id, author = null, coverPath = null, sourceUri = "",
        sourceKind = SourceKind.SINGLE_FILE, durationMs = 0, addedAtUtc = 0,
        origin = origin,
    )

    @Before fun setUp() {
        db = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext(), OratorDatabase::class.java,
        ).allowMainThreadQueries().build()
        dao = db.bookDao()
    }

    @After fun tearDown() = db.close()

    @Test fun `getByOrigin returns only matching books`() = runBlocking {
        dao.upsert(listOf(book("local1", BookOrigin.LOCAL), book("abs:1", BookOrigin.ABS)))
        assertEquals(listOf("abs:1"), dao.getByOrigin(BookOrigin.ABS).map { it.id })
        assertEquals(listOf("abs:1"), dao.getIdsByOrigin(BookOrigin.ABS))
    }

    @Test fun `description and series round-trip`() = runBlocking {
        dao.upsert(listOf(
            book("abs:9", BookOrigin.ABS).copy(description = "A focused life.", series = "Foundation #2"),
        ))
        val row = dao.getById("abs:9")!!
        assertEquals("A focused life.", row.description)
        assertEquals("Foundation #2", row.series)
    }

    @Test fun `observeCount tracks inserts and deletes`() = runBlocking {
        assertEquals(0, dao.observeCount().first())

        dao.upsert(listOf(book("a", BookOrigin.LOCAL), book("b", BookOrigin.ABS)))
        assertEquals(2, dao.observeCount().first())

        dao.deleteByIds(listOf("a"))
        assertEquals(1, dao.observeCount().first())
    }

    @Test fun `defaults are LOCAL and NONE`() = runBlocking {
        dao.upsert(listOf(BookEntity(
            id = "l", title = "l", author = null, coverPath = null, sourceUri = "",
            sourceKind = SourceKind.SINGLE_FILE, durationMs = 0, addedAtUtc = 0,
        )))
        val row = dao.getById("l")!!
        assertEquals(BookOrigin.LOCAL, row.origin)
        assertEquals(DownloadState.NONE, row.downloadState)
    }
}
