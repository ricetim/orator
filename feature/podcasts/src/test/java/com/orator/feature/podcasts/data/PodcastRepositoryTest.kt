package com.orator.feature.podcasts.data

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.orator.core.database.OratorDatabase
import com.orator.core.network.FeedFetcher
import com.orator.core.network.FetchResult
import com.orator.core.playback.NewEpisodeListener
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

private const val FEED_A = "https://example.com/a.xml"
private const val FEED_B = "https://example.com/b.xml"

private fun rss(title: String, vararg items: Pair<String, String>) = buildString {
    append("""<?xml version="1.0"?><rss version="2.0"><channel><title>$title</title>""")
    for ((guid, itemTitle) in items) {
        append(
            """<item><title>$itemTitle</title><guid>$guid</guid>""" +
                """<enclosure url="https://example.com/$guid.mp3" type="audio/mpeg"/></item>""",
        )
    }
    append("</channel></rss>")
}

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class PodcastRepositoryTest {

    private lateinit var db: OratorDatabase
    private lateinit var repository: PodcastRepository
    private val responses = mutableMapOf<String, FetchResult>()

    /** FeedFetcher is open precisely so tests can fake it without interface ceremony. */
    private val fetcher = object : FeedFetcher(OkHttpClient()) {
        override suspend fun fetch(url: String, etag: String?, lastModified: String?): FetchResult =
            responses[url] ?: FetchResult.Failure("no stub for $url")
    }

    /** Records every onNewEpisodes(...) call so tests can assert the auto-insert seam fired. */
    private val newEpisodeCalls = mutableListOf<List<String>>()
    private val recordingListener = object : NewEpisodeListener {
        override suspend fun onNewEpisodes(episodeIds: List<String>) { newEpisodeCalls += episodeIds }
    }

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(context, OratorDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        repository = PodcastRepository(
            fetcher = fetcher,
            podcastDao = db.podcastDao(),
            episodeDao = db.episodeDao(),
            cacheWriter = EpisodeCacheWriter(context, PodcastsFolderStore(context)),
            client = OkHttpClient(),
            newEpisodeListeners = setOf(recordingListener),
        )
    }

    @After
    fun tearDown() {
        db.close()
    }

    @Test
    fun `subscribe stores podcast and episodes`() = runBlocking {
        responses[FEED_A] =
            FetchResult.Success(rss("Show A", "g1" to "One", "g2" to "Two"), "\"v1\"", null)

        val id = repository.subscribe(FEED_A).getOrThrow()

        val podcast = db.podcastDao().getById(id)!!
        assertEquals("Show A", podcast.title)
        assertEquals("\"v1\"", podcast.etag)
        assertEquals(2, db.episodeDao().latestForPodcast(id, 10).size)
    }

    @Test
    fun `subscribe twice is idempotent`() = runBlocking {
        responses[FEED_A] = FetchResult.Success(rss("Show A", "g1" to "One"), null, null)
        val id1 = repository.subscribe(FEED_A).getOrThrow()
        val id2 = repository.subscribe(FEED_A).getOrThrow()
        assertEquals(id1, id2)
        assertEquals(1, db.podcastDao().getAll().size)
    }

    @Test
    fun `subscribe to broken feed fails without writing rows`() = runBlocking {
        responses[FEED_A] = FetchResult.Success("<html>not rss</html>", null, null)
        assertTrue(repository.subscribe(FEED_A).isFailure)
        assertEquals(0, db.podcastDao().getAll().size)
    }

    @Test
    fun `refresh with 304 touches nothing`() = runBlocking {
        responses[FEED_A] = FetchResult.Success(rss("Show A", "g1" to "One"), "\"v1\"", null)
        val id = repository.subscribe(FEED_A).getOrThrow()
        responses[FEED_A] = FetchResult.NotModified

        val summary = repository.refreshAll()

        assertEquals(1, summary.refreshed)
        assertEquals(0, summary.failed)
        assertEquals("One", db.episodeDao().latestForPodcast(id, 10).single().title)
    }

    @Test
    fun `refresh preserves position and audioPath but updates metadata`() = runBlocking {
        responses[FEED_A] = FetchResult.Success(rss("Show A", "g1" to "One"), null, null)
        val id = repository.subscribe(FEED_A).getOrThrow()
        val episodeId = db.episodeDao().latestForPodcast(id, 10).single().id
        db.episodeDao().updateProgress(episodeId, 5_000, 99)
        db.episodeDao().updateAudioPath(episodeId, "content://dl/audio.mp3")
        responses[FEED_A] = FetchResult.Success(
            rss("Show A", "g1" to "One (remastered)", "g2" to "Two"), null, null,
        )

        repository.refreshAll()

        val episodes = db.episodeDao().latestForPodcast(id, 10)
        assertEquals(2, episodes.size)
        val updated = db.episodeDao().getById(episodeId)!!
        assertEquals("One (remastered)", updated.title)
        assertEquals(5_000L, updated.positionMs)
        assertEquals("content://dl/audio.mp3", updated.audioPath)
    }

    @Test
    fun `one failing feed does not block the others`() = runBlocking {
        responses[FEED_A] = FetchResult.Success(rss("Show A", "g1" to "One"), null, null)
        responses[FEED_B] = FetchResult.Success(rss("Show B", "g1" to "Uno"), null, null)
        repository.subscribe(FEED_A).getOrThrow()
        val idB = repository.subscribe(FEED_B).getOrThrow()
        responses[FEED_A] = FetchResult.Failure("boom")
        responses[FEED_B] =
            FetchResult.Success(rss("Show B", "g1" to "Uno", "g2" to "Dos"), null, null)

        val summary = repository.refreshAll()

        assertEquals(1, summary.refreshed)
        assertEquals(1, summary.failed)
        assertEquals(2, db.episodeDao().latestForPodcast(idB, 10).size)
    }

    @Test
    fun `importOpml subscribes all feeds and isolates failures`() = runBlocking {
        responses[FEED_A] = FetchResult.Success(rss("Show A", "g1" to "One"), null, null)
        responses[FEED_B] = FetchResult.Failure("unreachable")
        val opml = """<opml version="2.0"><body>""" +
            """<outline text="A" xmlUrl="$FEED_A"/><outline text="B" xmlUrl="$FEED_B"/>""" +
            """</body></opml>"""

        val summary = repository.importOpml(opml)

        assertEquals(1, summary.refreshed)
        assertEquals(1, summary.failed)
        assertEquals(1, db.podcastDao().getAll().size)
    }

    @Test
    fun `unsubscribe removes podcast and episodes`() = runBlocking {
        responses[FEED_A] =
            FetchResult.Success(rss("Show A", "g1" to "One", "g2" to "Two"), null, null)
        val id = repository.subscribe(FEED_A).getOrThrow()

        repository.unsubscribe(id)

        assertEquals(0, db.podcastDao().getAll().size)
        assertEquals(0, db.episodeDao().latestForPodcast(id, 10).size)
    }

    @Test
    fun `unsubscribe is idempotent`() = runBlocking {
        responses[FEED_A] = FetchResult.Success(rss("Show A", "g1" to "One"), null, null)
        val id = repository.subscribe(FEED_A).getOrThrow()
        repository.unsubscribe(id)
        repository.unsubscribe(id) // second call must not throw
        assertEquals(0, db.podcastDao().getAll().size)
    }

    @Test
    fun `refresh fires NewEpisodeListener with only the new episode ids`() = runBlocking {
        responses[FEED_A] = FetchResult.Success(rss("Show A", "g1" to "One"), null, null)
        val id = repository.subscribe(FEED_A).getOrThrow()
        assertTrue(newEpisodeCalls.isEmpty()) // subscribe does not auto-insert (future-only)

        responses[FEED_A] =
            FetchResult.Success(rss("Show A", "g1" to "One", "g2" to "Two"), null, null)
        repository.refreshAll()

        assertEquals(1, newEpisodeCalls.size)
        assertEquals(listOf(PodcastIds.episodeId(id, "g2")), newEpisodeCalls.single())
    }

    @Test
    fun `a refresh with no new episodes does not fire the listener`() = runBlocking {
        responses[FEED_A] = FetchResult.Success(rss("Show A", "g1" to "One"), "\"v1\"", null)
        repository.subscribe(FEED_A).getOrThrow()
        responses[FEED_A] = FetchResult.NotModified // 304 — nothing new

        repository.refreshAll()

        assertTrue(newEpisodeCalls.isEmpty())
    }

    @Test
    fun `same guid across two shows stays two episodes`() = runBlocking {
        responses[FEED_A] = FetchResult.Success(rss("Show A", "g1" to "One"), null, null)
        responses[FEED_B] = FetchResult.Success(rss("Show B", "g1" to "Uno"), null, null)
        val idA = repository.subscribe(FEED_A).getOrThrow()
        val idB = repository.subscribe(FEED_B).getOrThrow()
        assertEquals(1, db.episodeDao().latestForPodcast(idA, 10).size)
        assertEquals(1, db.episodeDao().latestForPodcast(idB, 10).size)
    }
}
