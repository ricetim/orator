package com.orator.feature.playlists.playback

import com.orator.core.model.MediaRef
import com.orator.core.model.MediaType
import com.orator.core.playback.PlayRequest
import com.orator.core.playback.PlayRequestFactory
import com.orator.core.playback.PlayableItem
import com.orator.core.playback.PlaybackUiState
import com.orator.core.playback.ids.AudiobookMediaId
import com.orator.core.playback.ids.PodcastMediaId
import com.orator.feature.playlists.data.ActivePlaylist
import com.orator.feature.playlists.data.FakePlaylistDao
import com.orator.feature.playlists.data.PlaylistRepository
import com.orator.feature.playlists.data.item
import com.orator.feature.playlists.data.playlist
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PlaylistPlaybackControllerTest {

    private class FakePlayback : PlaylistPlayback {
        override val state = MutableStateFlow(PlaybackUiState())
        val played = mutableListOf<PlayRequest>()
        override fun play(request: PlayRequest) { played += request }
    }

    private class FakeActivePlaylist : ActivePlaylist {
        private var id: Long? = null
        override suspend fun activePlaylistId(): Long? = id
        override suspend fun set(playlistId: Long) { id = playlistId }
        override suspend fun clear() { id = null }
    }

    // factory whose PlayRequest encodes the ref so we can assert which item loaded
    private fun factory(type: MediaType) = object : PlayRequestFactory {
        override val mediaType = type
        override suspend fun create(ref: MediaRef): PlayRequest {
            val mediaId = if (type == MediaType.PODCAST) PodcastMediaId.encode(ref.id)
            else AudiobookMediaId.encode(ref.id, 0)
            return PlayRequest(
                items = listOf(PlayableItem(mediaId = mediaId, uri = "u", title = ref.id, artist = "")),
                mediaType = type,
            )
        }
    }

    private fun controller(dao: FakePlaylistDao, playback: FakePlayback, active: ActivePlaylist) =
        PlaylistPlaybackController(
            playback = playback,
            repo = PlaylistRepository(dao, emptySet()),
            factories = setOf(factory(MediaType.PODCAST), factory(MediaType.AUDIOBOOK)),
            active = active,
        )

    private fun ended() = PlaybackUiState(isEnded = true)
    private fun playing(mediaId: String) = PlaybackUiState(isEnded = false, mediaId = mediaId)

    @Test fun `playFromTop loads the top item and marks the playlist active`() = runTest {
        val dao = FakePlaylistDao(); val pb = FakePlayback(); val active = FakeActivePlaylist()
        val p = dao.insertPlaylist(playlist())
        dao.insertItem(item(p, MediaType.PODCAST, "ep1", 10))
        val c = controller(dao, pb, active)

        c.playFromTop(p)

        assertEquals("ep1", pb.played.single().items.single().title)
        assertEquals(p, active.activePlaylistId())
    }

    @Test fun `isEnded rising edge pops the top and plays the next`() = runTest {
        val dao = FakePlaylistDao(); val pb = FakePlayback(); val active = FakeActivePlaylist()
        val p = dao.insertPlaylist(playlist())
        dao.insertItem(item(p, MediaType.PODCAST, "ep1", 10))
        dao.insertItem(item(p, MediaType.AUDIOBOOK, "bk1", 20))
        val c = controller(dao, pb, active)
        c.playFromTop(p)
        c.onState(playing(PodcastMediaId.encode("ep1")))

        c.onState(ended())

        assertEquals(listOf("ep1", "bk1"), pb.played.map { it.items.single().title })
        assertEquals(listOf("bk1"), dao.getItems(p).map { it.mediaId })
    }

    @Test fun `two consecutive completions each advance (re-arm)`() = runTest {
        val dao = FakePlaylistDao(); val pb = FakePlayback(); val active = FakeActivePlaylist()
        val p = dao.insertPlaylist(playlist())
        dao.insertItem(item(p, MediaType.PODCAST, "ep1", 10))
        dao.insertItem(item(p, MediaType.PODCAST, "ep2", 20))
        dao.insertItem(item(p, MediaType.PODCAST, "ep3", 30))
        val c = controller(dao, pb, active)
        c.playFromTop(p)

        c.onState(playing(PodcastMediaId.encode("ep1")))
        c.onState(ended())
        c.onState(playing(PodcastMediaId.encode("ep2")))
        c.onState(ended())

        assertEquals(listOf("ep1", "ep2", "ep3"), pb.played.map { it.items.single().title })
    }

    @Test fun `empty after last completion stops and clears active`() = runTest {
        val dao = FakePlaylistDao(); val pb = FakePlayback(); val active = FakeActivePlaylist()
        val p = dao.insertPlaylist(playlist())
        dao.insertItem(item(p, MediaType.PODCAST, "ep1", 10))
        val c = controller(dao, pb, active)
        c.playFromTop(p)
        c.onState(playing(PodcastMediaId.encode("ep1")))

        c.onState(ended())

        assertEquals(1, pb.played.size)
        assertNull(active.activePlaylistId())
    }

    @Test fun `playItem promotes then plays`() = runTest {
        val dao = FakePlaylistDao(); val pb = FakePlayback(); val active = FakeActivePlaylist()
        val p = dao.insertPlaylist(playlist())
        dao.insertItem(item(p, MediaType.PODCAST, "ep1", 10))
        val second = dao.insertItem(item(p, MediaType.PODCAST, "ep2", 20))
        val c = controller(dao, pb, active)

        c.playItem(p, second)

        assertEquals("ep2", pb.played.single().items.single().title)
        assertEquals(listOf("ep2", "ep1"), dao.getItems(p).map { it.mediaId })
    }

    @Test fun `multi-file book internal transition does not advance`() = runTest {
        val dao = FakePlaylistDao(); val pb = FakePlayback(); val active = FakeActivePlaylist()
        val p = dao.insertPlaylist(playlist())
        dao.insertItem(item(p, MediaType.AUDIOBOOK, "bk1", 10))
        dao.insertItem(item(p, MediaType.PODCAST, "ep1", 20))
        val c = controller(dao, pb, active)
        c.playFromTop(p)

        c.onState(playing(AudiobookMediaId.encode("bk1", 0)))
        c.onState(playing(AudiobookMediaId.encode("bk1", 1)))

        assertEquals(listOf("bk1"), pb.played.map { it.items.single().title })
        assertEquals(2, dao.getItems(p).size)
    }

    @Test fun `playing something outside the playlist deactivates`() = runTest {
        val dao = FakePlaylistDao(); val pb = FakePlayback(); val active = FakeActivePlaylist()
        val p = dao.insertPlaylist(playlist())
        dao.insertItem(item(p, MediaType.PODCAST, "ep1", 10))
        val c = controller(dao, pb, active)
        c.playFromTop(p)

        c.onState(playing(PodcastMediaId.encode("other-ep")))

        assertNull(active.activePlaylistId())
    }

    @Test fun `no advance when no playlist is active`() = runTest {
        val dao = FakePlaylistDao(); val pb = FakePlayback(); val active = FakeActivePlaylist()
        val c = controller(dao, pb, active)

        c.onState(ended())

        assertEquals(0, pb.played.size)
    }
}
