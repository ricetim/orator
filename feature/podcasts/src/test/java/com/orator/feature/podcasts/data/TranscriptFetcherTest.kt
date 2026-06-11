package com.orator.feature.podcasts.data

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.orator.core.database.EpisodeEntity
import com.orator.core.database.OratorDatabase
import com.orator.core.database.PodcastEntity
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
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
class TranscriptFetcherTest {

    private lateinit var db: OratorDatabase
    private lateinit var server: MockWebServer
    private lateinit var fetcher: TranscriptFetcher

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(context, OratorDatabase::class.java)
            .allowMainThreadQueries().build()
        server = MockWebServer()
        server.start()
        fetcher = TranscriptFetcher(
            client = OkHttpClient(),
            podcastDao = db.podcastDao(),
            episodeDao = db.episodeDao(),
            cacheWriter = EpisodeCacheWriter(context, PodcastsFolderStore(context)),
        )
    }

    @After
    fun tearDown() {
        db.close()
        server.shutdown()
    }

    private fun seed(transcriptUrl: String?, transcriptPath: String? = null) = runBlocking {
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
                    transcriptUrl = transcriptUrl, transcriptType = "text/vtt",
                ),
            ),
        )
        transcriptPath?.let { db.episodeDao().updateTranscriptPath("e1", it) }
    }

    @Test
    fun `no transcript url fails fast without a request`() = runBlocking {
        seed(transcriptUrl = null)
        assertTrue(fetcher.fetch("e1").isFailure)
        assertEquals(0, server.requestCount)
    }

    @Test
    fun `fetchIfAvailable skips when already fetched`() = runBlocking {
        seed(transcriptUrl = server.url("/t.vtt").toString(), transcriptPath = "content://x/t.vtt")
        fetcher.fetchIfAvailable("e1")
        assertEquals(0, server.requestCount)
    }

    @Test
    fun `no granted cache folder maps to failure`() = runBlocking {
        seed(transcriptUrl = server.url("/t.vtt").toString())
        server.enqueue(MockResponse().setBody("WEBVTT\n\n00:00.000 --> 00:01.000\nHi"))

        val result = fetcher.fetch("e1")

        assertTrue(result.isFailure) // SAF tree absent in tests; the write returns null
        assertEquals(null, db.episodeDao().getById("e1")!!.transcriptPath)
    }

    @Test
    fun `extension follows type then url`() {
        assertEquals("vtt", TranscriptFetcher.transcriptExt("text/vtt", "https://x/t"))
        assertEquals("srt", TranscriptFetcher.transcriptExt("application/x-subrip", "https://x/t"))
        assertEquals("json", TranscriptFetcher.transcriptExt("application/json", "https://x/t"))
        assertEquals("txt", TranscriptFetcher.transcriptExt("text/plain", "https://x/t"))
        assertEquals("srt", TranscriptFetcher.transcriptExt(null, "https://x/t.srt?a=1"))
        assertEquals("txt", TranscriptFetcher.transcriptExt(null, "https://x/t"))
    }
}
