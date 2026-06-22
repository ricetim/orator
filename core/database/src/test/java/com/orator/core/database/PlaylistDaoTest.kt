package com.orator.core.database

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.orator.core.model.MediaType
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
class PlaylistDaoTest {

    private val db = Room.inMemoryDatabaseBuilder(
        ApplicationProvider.getApplicationContext<Context>(),
        OratorDatabase::class.java,
    ).allowMainThreadQueries().build()

    private val dao = db.playlistDao()

    @After fun tearDown() = db.close()

    private suspend fun newPlaylist(name: String) =
        dao.insertPlaylist(PlaylistEntity(name = name, createdAtMs = 0))

    private fun item(playlistId: Long, mediaId: String, pos: Long, type: MediaType = MediaType.PODCAST) =
        PlaylistItemEntity(playlistId = playlistId, mediaType = type, mediaId = mediaId, position = pos)

    @Test fun `items come back ordered by position`() = runBlocking {
        val p = newPlaylist("Mix")
        dao.insertItem(item(p, "b", pos = 20))
        dao.insertItem(item(p, "a", pos = 10))
        dao.insertItem(item(p, "c", pos = 30))

        assertEquals(listOf("a", "b", "c"), dao.observeItems(p).first().map { it.mediaId })
    }

    @Test fun `duplicate add of same ref is ignored`() = runBlocking {
        val p = newPlaylist("Mix")
        dao.insertItem(item(p, "a", pos = 10))
        dao.insertItem(item(p, "a", pos = 20)) // same (playlist, type, mediaId)

        assertEquals(1, dao.observeItems(p).first().size)
    }

    @Test fun `deleting a playlist cascades its items`() = runBlocking {
        val p = newPlaylist("Mix")
        val a = dao.insertItem(item(p, "a", pos = 10))
        dao.deletePlaylist(p)

        assertEquals(0, dao.observeItems(p).first().size)
        assertNull(dao.getItem(a))
    }

    @Test fun `updatePositions rewrites order`() = runBlocking {
        val p = newPlaylist("Mix")
        val a = dao.insertItem(item(p, "a", pos = 10))
        val b = dao.insertItem(item(p, "b", pos = 20))

        dao.updatePositions(listOf(PlaylistItemPosition(b, 10), PlaylistItemPosition(a, 20)))

        assertEquals(listOf("b", "a"), dao.observeItems(p).first().map { it.mediaId })
    }

    @Test fun `deleteItem removes a single row`() = runBlocking {
        val p = newPlaylist("Mix")
        val a = dao.insertItem(item(p, "a", pos = 10))
        dao.insertItem(item(p, "b", pos = 20))

        dao.deleteItem(a)

        assertEquals(listOf("b"), dao.observeItems(p).first().map { it.mediaId })
    }

    @Test fun `observePlaylists reports item counts`() = runBlocking {
        val p = newPlaylist("Mix")
        newPlaylist("Empty")
        dao.insertItem(item(p, "a", pos = 10))
        dao.insertItem(item(p, "b", pos = 20))

        val counts = dao.observePlaylists().first().associate { it.name to it.itemCount }

        assertEquals(mapOf("Mix" to 2, "Empty" to 0), counts)
    }

    @Test fun `minPosition returns the smallest position, null when empty`() = runBlocking {
        val p = newPlaylist("Mix")
        assertNull(dao.minPosition(p))
        dao.insertItem(item(p, "a", pos = 30))
        dao.insertItem(item(p, "b", pos = 10))
        assertEquals(10L, dao.minPosition(p))
    }

    @Test fun `getTopItem returns the smallest-position row`() = runBlocking {
        val p = newPlaylist("Mix")
        assertNull(dao.getTopItem(p))
        dao.insertItem(item(p, "late", pos = 30))
        dao.insertItem(item(p, "first", pos = 10))

        assertEquals("first", dao.getTopItem(p)!!.mediaId)
    }
}
