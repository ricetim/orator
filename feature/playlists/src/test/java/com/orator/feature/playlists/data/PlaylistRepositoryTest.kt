package com.orator.feature.playlists.data

import com.orator.core.model.MediaRef
import com.orator.core.model.MediaType
import com.orator.core.model.PlaylistItemContent
import com.orator.core.model.PlaylistItemResolver
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PlaylistRepositoryTest {

    private fun resolver(type: MediaType, known: Set<String>) = object : PlaylistItemResolver {
        override val mediaType = type
        override suspend fun resolve(ref: MediaRef): PlaylistItemContent? =
            if (ref.id in known) PlaylistItemContent("T:${ref.id}", "S", null, 1000) else null
    }

    @Test fun `hydrates mixed rows in order`() = runTest {
        val dao = FakePlaylistDao()
        val p = dao.insertPlaylist(playlist())
        dao.insertItem(item(p, MediaType.PODCAST, "ep", 10))
        dao.insertItem(item(p, MediaType.AUDIOBOOK, "bk", 20))
        val repo = PlaylistRepository(
            dao,
            setOf(resolver(MediaType.PODCAST, setOf("ep")), resolver(MediaType.AUDIOBOOK, setOf("bk"))),
        )

        val ui = repo.items(p)

        assertEquals(listOf("T:ep", "T:bk"), ui.map { it.content.title })
        assertEquals(listOf(MediaType.PODCAST, MediaType.AUDIOBOOK), ui.map { it.ref.type })
    }

    @Test fun `prunes a dangling ref and deletes its row`() = runTest {
        val dao = FakePlaylistDao()
        val p = dao.insertPlaylist(playlist())
        dao.insertItem(item(p, MediaType.PODCAST, "ep", 10))
        val gone = dao.insertItem(item(p, MediaType.PODCAST, "ghost", 20))
        val repo = PlaylistRepository(dao, setOf(resolver(MediaType.PODCAST, setOf("ep"))))

        val ui = repo.items(p)

        assertEquals(listOf("T:ep"), ui.map { it.content.title })
        assertNull(dao.getItem(gone)) // row deleted
    }

    @Test fun `topRef returns the smallest-position ref, null when empty`() = runTest {
        val dao = FakePlaylistDao()
        val p = dao.insertPlaylist(playlist())
        val repo = PlaylistRepository(dao, emptySet())
        assertNull(repo.topRef(p))
        dao.insertItem(item(p, MediaType.AUDIOBOOK, "bk", 30))
        dao.insertItem(item(p, MediaType.PODCAST, "ep", 10))
        assertEquals(MediaRef(MediaType.PODCAST, "ep"), repo.topRef(p))
    }

    @Test fun `addToBottom appends after the current max position`() = runTest {
        val dao = FakePlaylistDao()
        val p = dao.insertPlaylist(playlist())
        val repo = PlaylistRepository(dao, emptySet())

        repo.addToBottom(p, MediaRef(MediaType.PODCAST, "a"))
        repo.addToBottom(p, MediaRef(MediaType.PODCAST, "b"))

        assertEquals(listOf("a", "b"), dao.getItems(p).map { it.mediaId })
    }

    @Test fun `moveToTop promotes and persists dense order`() = runTest {
        val dao = FakePlaylistDao()
        val p = dao.insertPlaylist(playlist())
        dao.insertItem(item(p, MediaType.PODCAST, "a", 10))
        val b = dao.insertItem(item(p, MediaType.PODCAST, "b", 20))
        val repo = PlaylistRepository(dao, emptySet())

        repo.moveToTop(p, b)

        assertEquals(listOf("b", "a"), dao.getItems(p).map { it.mediaId })
    }
}
