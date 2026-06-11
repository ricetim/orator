package com.orator.core.database

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class EpisodeDaoTest {

    private val db = Room.inMemoryDatabaseBuilder(
        ApplicationProvider.getApplicationContext<Context>(),
        OratorDatabase::class.java,
    ).allowMainThreadQueries().build()

    private val dao = db.episodeDao()

    @After
    fun tearDown() = db.close()

    private fun episode(id: String, podcastId: String = "p1", pubDate: Long = 0) = EpisodeEntity(
        id = id, podcastId = podcastId, title = "Ep $id", pubDateUtc = pubDate,
        enclosureUrl = "https://x/$id.mp3",
    )

    @Test
    fun `insertIgnore leaves existing rows untouched`() = runBlocking {
        dao.insertIgnore(listOf(episode("e1")))
        dao.updateProgress("e1", positionMs = 5_000, lastPlayedAtMs = 42)

        dao.insertIgnore(listOf(episode("e1").copy(title = "Changed"), episode("e2")))

        val row = dao.getById("e1")!!
        assertEquals("Ep e1", row.title)
        assertEquals(5_000L, row.positionMs)
        assertEquals(2, dao.latestForPodcast("p1", 10).size)
    }

    @Test
    fun `updateMetadata preserves position and audioPath`() = runBlocking {
        dao.insertIgnore(listOf(episode("e1")))
        dao.updateProgress("e1", 5_000, 42)
        dao.updateAudioPath("e1", "content://dl/a.mp3")

        dao.updateMetadata(
            id = "e1", title = "Remastered", pubDateUtc = 7,
            enclosureUrl = "https://x/e1-v2.mp3", showNotesHtml = "<p>notes</p>",
            transcriptUrl = null, transcriptType = null, durationMs = 0,
        )

        val row = dao.getById("e1")!!
        assertEquals("Remastered", row.title)
        assertEquals("<p>notes</p>", row.showNotesHtml)
        assertEquals(5_000L, row.positionMs)
        assertEquals("content://dl/a.mp3", row.audioPath)
    }

    @Test
    fun `updateMetadata duration only improves`() = runBlocking {
        dao.insertIgnore(listOf(episode("e1").copy(durationMs = 600_000)))

        dao.updateMetadata("e1", "Ep e1", 0, "https://x/e1.mp3", null, transcriptUrl = null, transcriptType = null, durationMs = 0)
        assertEquals(600_000L, dao.getById("e1")!!.durationMs) // 0 never erases

        dao.updateMetadata("e1", "Ep e1", 0, "https://x/e1.mp3", null, transcriptUrl = null, transcriptType = null, durationMs = 700_000)
        assertEquals(700_000L, dao.getById("e1")!!.durationMs) // >0 updates
    }

    @Test
    fun `updateMetadata sets transcript url and type but never transcriptPath`() = runBlocking {
        dao.insertIgnore(listOf(episode("e1")))
        dao.updateTranscriptPath("e1", "content://t/transcript.vtt")

        dao.updateMetadata(
            id = "e1", title = "Ep e1", pubDateUtc = 0, enclosureUrl = "https://x/e1.mp3",
            showNotesHtml = null, transcriptUrl = "https://x/t.vtt", transcriptType = "text/vtt",
            durationMs = 0,
        )

        val row = dao.getById("e1")!!
        assertEquals("https://x/t.vtt", row.transcriptUrl)
        assertEquals("text/vtt", row.transcriptType)
        assertEquals("content://t/transcript.vtt", row.transcriptPath)
    }

    @Test
    fun `backfillDuration writes only when unknown`() = runBlocking {
        dao.insertIgnore(listOf(episode("e1")))
        dao.backfillDuration("e1", 600_000)
        assertEquals(600_000L, dao.getById("e1")!!.durationMs)

        dao.backfillDuration("e1", 1)
        assertEquals(600_000L, dao.getById("e1")!!.durationMs) // no-op once known
    }

    @Test
    fun `observeForPodcast orders newest first and latestForPodcast limits`() = runBlocking {
        dao.insertIgnore(
            listOf(episode("e1", pubDate = 100), episode("e2", pubDate = 300), episode("e3", pubDate = 200)),
        )

        val observed = dao.observeForPodcast("p1").first()
        assertEquals(listOf("e2", "e3", "e1"), observed.map { it.id })
        assertEquals(listOf("e2", "e3"), dao.latestForPodcast("p1", 2).map { it.id })
    }
}
