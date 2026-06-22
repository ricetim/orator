package com.orator.core.database

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.orator.core.model.AutoInsertRule
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class PodcastDaoTest {

    private val db = Room.inMemoryDatabaseBuilder(
        ApplicationProvider.getApplicationContext<Context>(),
        OratorDatabase::class.java,
    ).allowMainThreadQueries().build()

    private val dao = db.podcastDao()

    @After
    fun tearDown() = db.close()

    private fun podcast(id: String = "p1", title: String = "Show") = PodcastEntity(
        id = id, feedUrl = "https://x/feed.xml", title = title, author = null,
        description = null, artworkUrl = null, subscribedAtUtc = 0,
    )

    @Test
    fun `insertIgnore is idempotent and keeps the original row`() = runBlocking {
        dao.insertIgnore(podcast(title = "Original"))
        val second = dao.insertIgnore(podcast(title = "Replacement"))

        assertEquals(-1L, second)
        assertEquals("Original", dao.getById("p1")!!.title)
    }

    @Test
    fun `updateClips round-trips`() = runBlocking {
        dao.insertIgnore(podcast())
        dao.updateClips("p1", introMs = 30_000, outroMs = 60_000)

        val row = dao.getById("p1")!!
        assertEquals(30_000L, row.clipIntroMs)
        assertEquals(60_000L, row.clipOutroMs)
    }

    @Test
    fun `updateSpeedOverride sets and clears`() = runBlocking {
        dao.insertIgnore(podcast())
        dao.updateSpeedOverride("p1", 1.5f)
        assertEquals(1.5f, dao.getById("p1")!!.speedOverride)

        dao.updateSpeedOverride("p1", null)
        assertNull(dao.getById("p1")!!.speedOverride)
    }

    @Test
    fun `updateFeedMeta refreshes metadata and validators only`() = runBlocking {
        dao.insertIgnore(podcast())
        dao.updateClips("p1", 5_000, 0)

        dao.updateFeedMeta(
            id = "p1", title = "New Title", author = "Jane", description = "About",
            artworkUrl = "https://x/c.jpg", refreshedAtUtc = 99, etag = "\"v2\"",
            lastModified = "yesterday",
        )

        val row = dao.getById("p1")!!
        assertEquals("New Title", row.title)
        assertEquals("\"v2\"", row.etag)
        assertEquals(5_000L, row.clipIntroMs) // untouched by feed refresh
        assertTrue(row.lastRefreshUtc == 99L)
    }

    @Test
    fun `updateAutoInsert sets and clears the target + rule`() = runBlocking {
        dao.insertIgnore(podcast("p1"))

        dao.updateAutoInsert("p1", playlistId = 5L, rule = AutoInsertRule.NEW_TO_TOP)
        dao.getById("p1")!!.let {
            assertEquals(5L, it.autoInsertPlaylistId)
            assertEquals(AutoInsertRule.NEW_TO_TOP, it.autoInsertRule)
        }

        dao.updateAutoInsert("p1", playlistId = null, rule = null)
        dao.getById("p1")!!.let {
            assertNull(it.autoInsertPlaylistId)
            assertNull(it.autoInsertRule)
        }
    }
}
