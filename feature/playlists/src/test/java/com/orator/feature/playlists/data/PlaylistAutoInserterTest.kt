package com.orator.feature.playlists.data

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.orator.core.database.EpisodeEntity
import com.orator.core.database.OratorDatabase
import com.orator.core.database.PlaylistEntity
import com.orator.core.database.PlaylistItemEntity
import com.orator.core.database.PodcastEntity
import com.orator.core.model.AutoInsertRule
import com.orator.core.model.MediaType
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class PlaylistAutoInserterTest {

    private val db = Room.inMemoryDatabaseBuilder(
        ApplicationProvider.getApplicationContext<Context>(),
        OratorDatabase::class.java,
    ).allowMainThreadQueries().build()

    private val playlistDao = db.playlistDao()
    private val repo = PlaylistRepository(playlistDao, emptySet())
    private val inserter = PlaylistAutoInserter(db.episodeDao(), db.podcastDao(), repo, playlistDao)

    @After fun tearDown() = db.close()

    private suspend fun newPlaylist() = playlistDao.insertPlaylist(PlaylistEntity(name = "L", createdAtMs = 0))
    private suspend fun podcast(id: String, target: Long?, rule: AutoInsertRule?) =
        db.podcastDao().insertIgnore(
            PodcastEntity(
                id = id, feedUrl = "https://x/$id.xml", title = "Show $id", author = null,
                description = null, artworkUrl = null, subscribedAtUtc = 0,
                autoInsertPlaylistId = target, autoInsertRule = rule,
            ),
        )
    private suspend fun episode(id: String, podcastId: String) =
        db.episodeDao().insertIgnore(
            listOf(EpisodeEntity(id = id, podcastId = podcastId, title = id, pubDateUtc = 0, enclosureUrl = "u")),
        )
    private suspend fun ids(playlistId: Long) = playlistDao.getItems(playlistId).map { it.mediaId }

    @Test fun `NEW_TO_TOP prepends the new episode`() = runBlocking {
        val p = newPlaylist()
        playlistDao.insertItem(PlaylistItemEntity(playlistId = p, mediaType = MediaType.AUDIOBOOK, mediaId = "book", position = 10))
        podcast("pod", target = p, rule = AutoInsertRule.NEW_TO_TOP); episode("e1", "pod")

        inserter.onNewEpisodes(listOf("e1"))

        assertEquals(listOf("e1", "book"), ids(p))
        assertEquals(MediaType.PODCAST, playlistDao.getTopItem(p)!!.mediaType)
    }

    @Test fun `NEW_TO_BOTTOM appends the new episode`() = runBlocking {
        val p = newPlaylist()
        playlistDao.insertItem(PlaylistItemEntity(playlistId = p, mediaType = MediaType.AUDIOBOOK, mediaId = "book", position = 10))
        podcast("pod", target = p, rule = AutoInsertRule.NEW_TO_BOTTOM); episode("e1", "pod")

        inserter.onNewEpisodes(listOf("e1"))

        assertEquals(listOf("book", "e1"), ids(p))
    }

    @Test fun `podcast without auto-insert config inserts nothing`() = runBlocking {
        val p = newPlaylist()
        podcast("pod", target = null, rule = null); episode("e1", "pod")

        inserter.onNewEpisodes(listOf("e1"))

        assertEquals(emptyList<String>(), ids(p))
    }

    @Test fun `dangling target playlist is skipped without crashing`() = runBlocking {
        val p = newPlaylist()
        podcast("pod", target = 9999L, rule = AutoInsertRule.NEW_TO_TOP); episode("e1", "pod")

        inserter.onNewEpisodes(listOf("e1")) // 9999 does not exist

        assertEquals(emptyList<String>(), ids(p))
    }

    @Test fun `duplicate episode is not inserted twice`() = runBlocking {
        val p = newPlaylist()
        podcast("pod", target = p, rule = AutoInsertRule.NEW_TO_BOTTOM); episode("e1", "pod")

        inserter.onNewEpisodes(listOf("e1"))
        inserter.onNewEpisodes(listOf("e1"))

        assertEquals(listOf("e1"), ids(p))
    }

    @Test fun `two podcasts route to their own targets and rules`() = runBlocking {
        val p1 = newPlaylist(); val p2 = newPlaylist()
        playlistDao.insertItem(PlaylistItemEntity(playlistId = p2, mediaType = MediaType.AUDIOBOOK, mediaId = "book", position = 10))
        podcast("podA", target = p1, rule = AutoInsertRule.NEW_TO_BOTTOM); episode("a1", "podA")
        podcast("podB", target = p2, rule = AutoInsertRule.NEW_TO_TOP); episode("b1", "podB")

        inserter.onNewEpisodes(listOf("a1", "b1"))

        assertEquals(listOf("a1"), ids(p1))
        assertEquals(listOf("b1", "book"), ids(p2)) // p2 is NEW_TO_TOP
    }
}
