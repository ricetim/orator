package com.orator.feature.podcasts.data

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.orator.core.database.EpisodeEntity
import com.orator.core.database.OratorDatabase
import com.orator.core.database.PodcastEntity
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class PodcastPositionListenerTest {

    private lateinit var db: OratorDatabase
    private lateinit var listener: PodcastPositionListener

    @Before
    fun setUp() {
        db = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext<Context>(),
            OratorDatabase::class.java,
        ).allowMainThreadQueries().build()
        listener = PodcastPositionListener(db.podcastDao(), db.episodeDao())
    }

    @After
    fun tearDown() = db.close()

    private suspend fun seed(introMs: Long = 0, durationMs: Long = 0) {
        db.podcastDao().insertIgnore(
            PodcastEntity(
                id = "p1", feedUrl = "https://x/f.xml", title = "Show", author = null,
                description = null, artworkUrl = null, subscribedAtUtc = 0,
                clipIntroMs = introMs,
            ),
        )
        db.episodeDao().insertIgnore(
            listOf(
                EpisodeEntity(
                    id = "e1", podcastId = "p1", title = "Ep", pubDateUtc = 0,
                    durationMs = durationMs, enclosureUrl = "https://x/e.mp3",
                ),
            ),
        )
    }

    @Test
    fun `persists clip-relative position and lastPlayedAt`() = runBlocking {
        seed()
        listener.onPositionChanged("podcast/e1", positionMs = 5_000, durationMs = 0)

        val row = db.episodeDao().getById("e1")!!
        assertEquals(5_000L, row.positionMs)
        assertTrue(row.lastPlayedAtMs > 0)
    }

    @Test
    fun `ignores non-podcast media ids`() = runBlocking {
        seed()
        listener.onPositionChanged("audiobook/x/0", positionMs = 5_000, durationMs = 0)

        assertEquals(0L, db.episodeDao().getById("e1")!!.positionMs)
    }

    @Test
    fun `backfills duration with intro offset when unknown`() = runBlocking {
        seed(introMs = 30_000, durationMs = 0)
        listener.onPositionChanged("podcast/e1", positionMs = 1_000, durationMs = 570_000)

        assertEquals(600_000L, db.episodeDao().getById("e1")!!.durationMs)
    }

    @Test
    fun `never overwrites a known duration`() = runBlocking {
        seed(durationMs = 600_000)
        listener.onPositionChanged("podcast/e1", positionMs = 1_000, durationMs = 1)

        assertEquals(600_000L, db.episodeDao().getById("e1")!!.durationMs)
    }

    @Test
    fun `no backfill when player duration unknown`() = runBlocking {
        seed(durationMs = 0)
        // Media3 reports C.TIME_UNSET before buffering settles; the service passes 0.
        listener.onPositionChanged("podcast/e1", positionMs = 1_000, durationMs = 0)

        assertEquals(0L, db.episodeDao().getById("e1")!!.durationMs)
    }
}
