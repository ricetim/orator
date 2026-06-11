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
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class EpisodeSpeedOverrideListenerTest {

    private lateinit var db: OratorDatabase
    private lateinit var listener: EpisodeSpeedOverrideListener

    @Before
    fun setUp() {
        db = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext<Context>(),
            OratorDatabase::class.java,
        ).allowMainThreadQueries().build()
        listener = EpisodeSpeedOverrideListener(db.podcastDao(), db.episodeDao())
        runBlocking {
            db.podcastDao().insertIgnore(
                PodcastEntity(
                    id = "p1", feedUrl = "https://x/f.xml", title = "Show", author = null,
                    description = null, artworkUrl = null, subscribedAtUtc = 0,
                ),
            )
            db.episodeDao().insertIgnore(
                listOf(
                    EpisodeEntity(
                        id = "e1", podcastId = "p1", title = "Ep", pubDateUtc = 0,
                        enclosureUrl = "https://x/e.mp3",
                    ),
                ),
            )
        }
    }

    @After
    fun tearDown() = db.close()

    @Test
    fun `sets the override on the episode's show`() = runBlocking {
        listener.onSpeedOverrideChanged("podcast/e1", 1.5f)
        assertEquals(1.5f, db.podcastDao().getById("p1")!!.speedOverride)
    }

    @Test
    fun `clears with null`() = runBlocking {
        listener.onSpeedOverrideChanged("podcast/e1", 1.5f)
        listener.onSpeedOverrideChanged("podcast/e1", null)
        assertNull(db.podcastDao().getById("p1")!!.speedOverride)
    }

    @Test
    fun `ignores non-podcast ids`() = runBlocking {
        listener.onSpeedOverrideChanged("audiobook/b1/0", 2.0f)
        assertNull(db.podcastDao().getById("p1")!!.speedOverride)
    }

    @Test
    fun `ignores unknown episode ids`() = runBlocking {
        listener.onSpeedOverrideChanged("podcast/nope", 2.0f)
        assertNull(db.podcastDao().getById("p1")!!.speedOverride)
    }
}
