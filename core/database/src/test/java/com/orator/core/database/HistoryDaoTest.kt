package com.orator.core.database

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class HistoryDaoTest {

    private val db = Room.inMemoryDatabaseBuilder(
        ApplicationProvider.getApplicationContext<Context>(),
        OratorDatabase::class.java,
    ).allowMainThreadQueries().build()

    private val dao = db.historyDao()

    @After
    fun tearDown() = db.close()

    @Test
    fun `insert and observe newest first`() = runBlocking {
        dao.insert(HistoryEntity(mediaId = "a/1/0", title = "One", mediaType = "AUDIOBOOK", startedAtUtc = 100))
        dao.insert(HistoryEntity(mediaId = "a/2/0", title = "Two", mediaType = "AUDIOBOOK", startedAtUtc = 200))

        val rows = dao.observeRecent(limit = 10).first()
        assertEquals(listOf("Two", "One"), rows.map { it.title })
        assertNull(rows.first().endedAtUtc) // open row = interrupted/ongoing
    }

    @Test
    fun `close marks the open row for a mediaId`() = runBlocking {
        val id = dao.insert(HistoryEntity(mediaId = "a/1/0", title = "One", mediaType = null, startedAtUtc = 100))
        dao.close(mediaId = "a/1/0", endedAtUtc = 500, completed = true)

        val row = dao.observeRecent(limit = 1).first().single()
        assertEquals(id, row.id)
        assertEquals(500L, row.endedAtUtc)
        assertEquals(true, row.completed)
    }

    @Test
    fun `close only touches open rows`() = runBlocking {
        dao.insert(HistoryEntity(mediaId = "a/1/0", title = "Old", mediaType = null, startedAtUtc = 100))
        dao.close(mediaId = "a/1/0", endedAtUtc = 150, completed = false)
        dao.insert(HistoryEntity(mediaId = "a/1/0", title = "New", mediaType = null, startedAtUtc = 200))
        dao.close(mediaId = "a/1/0", endedAtUtc = 900, completed = true)

        val rows = dao.observeRecent(limit = 10).first()
        assertEquals(150L, rows.single { it.title == "Old" }.endedAtUtc)
        assertEquals(900L, rows.single { it.title == "New" }.endedAtUtc)
    }
}
