package com.orator.feature.podcasts.data

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.orator.core.database.EpisodeEntity
import com.orator.core.database.OratorDatabase
import com.orator.core.database.PodcastEntity
import com.orator.core.model.MediaRef
import com.orator.core.model.MediaType
import com.orator.core.playback.ids.PodcastMediaId
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
class EpisodePlaylistContributionsTest {

    private val db = Room.inMemoryDatabaseBuilder(
        ApplicationProvider.getApplicationContext<Context>(),
        OratorDatabase::class.java,
    ).allowMainThreadQueries().build()

    private val factory = EpisodePlayRequestFactory(db.episodeDao(), db.podcastDao())
    private val resolver = EpisodePlaylistItemResolver(db.episodeDao(), db.podcastDao())

    @After fun tearDown() = db.close()

    private fun podcast(id: String) = PodcastEntity(
        id = id, feedUrl = "https://x/$id.xml", title = "Show $id", author = "A",
        description = null, artworkUrl = "https://x/$id.png", subscribedAtUtc = 0,
    )

    private fun episode(id: String, podcastId: String) = EpisodeEntity(
        id = id, podcastId = podcastId, title = "Ep $id", pubDateUtc = 0,
        durationMs = 600_000, enclosureUrl = "https://x/$id.mp3", positionMs = 1_500,
    )

    @Test fun `factory returns null for an unknown episode`() = runBlocking {
        assertNull(factory.create(MediaRef(MediaType.PODCAST, "missing")))
    }

    @Test fun `factory returns null when the episode's podcast is gone`() = runBlocking {
        db.episodeDao().insertIgnore(listOf(episode("e1", podcastId = "ghost")))
        assertNull(factory.create(MediaRef(MediaType.PODCAST, "e1")))
    }

    @Test fun `factory builds a resume PlayRequest for a known episode`() = runBlocking {
        db.podcastDao().insertIgnore(podcast("p1"))
        db.episodeDao().insertIgnore(listOf(episode("e1", podcastId = "p1")))

        val req = factory.create(MediaRef(MediaType.PODCAST, "e1"))!!

        assertEquals(MediaType.PODCAST, req.mediaType)
        assertEquals(1_500L, req.startPositionMs) // resumes at the episode's saved position
        assertEquals(PodcastMediaId.encode("e1"), req.items.first().mediaId)
        assertEquals("https://x/e1.mp3", req.items.first().uri)
    }

    @Test fun `resolver maps episode + show to display content`() = runBlocking {
        db.podcastDao().insertIgnore(podcast("p1"))
        db.episodeDao().insertIgnore(listOf(episode("e1", podcastId = "p1")))

        val content = resolver.resolve(MediaRef(MediaType.PODCAST, "e1"))!!

        assertEquals("Ep e1", content.title)
        assertEquals("Show p1", content.subtitle)
        assertEquals("https://x/p1.png", content.artworkUri)
        assertEquals(600_000L, content.durationMs)
    }

    @Test fun `resolver returns null for an unknown episode`() = runBlocking {
        assertNull(resolver.resolve(MediaRef(MediaType.PODCAST, "missing")))
    }
}
