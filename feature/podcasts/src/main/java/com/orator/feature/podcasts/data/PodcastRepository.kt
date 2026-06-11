package com.orator.feature.podcasts.data

import com.orator.core.database.EpisodeDao
import com.orator.core.database.EpisodeEntity
import com.orator.core.database.PodcastDao
import com.orator.core.database.PodcastEntity
import com.orator.core.network.FeedFetcher
import com.orator.core.network.FetchResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.atomic.AtomicInteger
import javax.inject.Inject
import javax.inject.Singleton

/** Written to the tree per show: latest N episodes (plus anything downloaded). Spec amendment. */
private const val TREE_EPISODE_LIMIT = 20

/** Parallel feed fetches during refresh-all; modest so a weak network isn't saturated. */
private const val REFRESH_CONCURRENCY = 6

@Singleton
class PodcastRepository @Inject constructor(
    private val fetcher: FeedFetcher,
    private val podcastDao: PodcastDao,
    private val episodeDao: EpisodeDao,
    private val cacheWriter: EpisodeCacheWriter,
    private val client: OkHttpClient,
) {

    data class RefreshSummary(val refreshed: Int, val failed: Int)

    val podcasts: Flow<List<PodcastEntity>> = podcastDao.observeAll()

    /** Non-null while a long operation runs; placeholder screens render it as a status line. */
    private val _busy = MutableStateFlow<String?>(null)
    val busy: StateFlow<String?> = _busy.asStateFlow()

    suspend fun subscribe(feedUrl: String): Result<String> = withContext(Dispatchers.IO) {
        val id = PodcastIds.podcastId(feedUrl)
        podcastDao.getById(id)?.let { return@withContext Result.success(id) }

        val fetched = fetcher.fetch(feedUrl)
        val success = fetched as? FetchResult.Success
            ?: return@withContext Result.failure(
                IllegalStateException("fetch failed: ${(fetched as? FetchResult.Failure)?.message}"),
            )
        val parsed = RssParser.parse(success.body)
            ?: return@withContext Result.failure(IllegalStateException("not a parsable RSS feed"))

        val podcast = PodcastEntity(
            id = id,
            feedUrl = feedUrl,
            title = parsed.title,
            author = parsed.author,
            description = parsed.description,
            artworkUrl = parsed.artworkUrl,
            subscribedAtUtc = System.currentTimeMillis(),
            lastRefreshUtc = System.currentTimeMillis(),
            etag = success.etag,
            lastModified = success.lastModified,
        )
        podcastDao.insertIgnore(podcast)
        upsertEpisodes(id, parsed)
        writeTree(podcast)
        Result.success(id)
    }

    suspend fun importOpml(opmlXml: String): RefreshSummary = withContext(Dispatchers.IO) {
        val feeds = OpmlParser.parse(opmlXml)
        var ok = 0
        var failed = 0
        feeds.forEachIndexed { index, feed ->
            _busy.value = "Importing ${index + 1}/${feeds.size}: ${feed.title}"
            if (subscribe(feed.xmlUrl).isSuccess) ok++ else failed++
        }
        _busy.value = null
        RefreshSummary(ok, failed)
    }

    /**
     * Feeds refresh in parallel ([REFRESH_CONCURRENCY] at a time): with manual-only refresh,
     * latency is the whole cost — 43 sequential round-trips of mostly-304s is needless waiting.
     * Counters are atomic because completions land on different workers.
     */
    suspend fun refreshAll(): RefreshSummary = withContext(Dispatchers.IO) {
        val all = podcastDao.getAll()
        val done = AtomicInteger(0)
        val failed = AtomicInteger(0)
        val gate = Semaphore(REFRESH_CONCURRENCY)
        coroutineScope {
            all.map { podcast ->
                async {
                    gate.withPermit {
                        if (!refresh(podcast)) failed.incrementAndGet()
                        _busy.value = "Refreshing ${done.incrementAndGet()}/${all.size}"
                    }
                }
            }.awaitAll()
        }
        _busy.value = null
        RefreshSummary(all.size - failed.get(), failed.get())
    }

    /** User decision: deletes everything, downloads included (UI confirms first). */
    suspend fun unsubscribe(podcastId: String) = withContext(Dispatchers.IO) {
        val podcast = podcastDao.getById(podcastId) ?: return@withContext
        episodeDao.deleteForPodcast(podcastId)
        podcastDao.delete(podcastId)
        cacheWriter.deleteShowDir(podcast) // after DB: rows must go even if the tree op fails
    }

    private suspend fun refresh(podcast: PodcastEntity): Boolean {
        return when (val result = fetcher.fetch(podcast.feedUrl, podcast.etag, podcast.lastModified)) {
            is FetchResult.NotModified -> {
                podcastDao.touchRefresh(podcast.id, System.currentTimeMillis())
                true
            }
            is FetchResult.Success -> {
                val parsed = RssParser.parse(result.body) ?: return false
                podcastDao.updateFeedMeta(
                    id = podcast.id,
                    title = parsed.title,
                    author = parsed.author,
                    description = parsed.description,
                    artworkUrl = parsed.artworkUrl,
                    refreshedAtUtc = System.currentTimeMillis(),
                    etag = result.etag,
                    lastModified = result.lastModified,
                )
                upsertEpisodes(podcast.id, parsed)
                writeTree(podcastDao.getById(podcast.id) ?: podcast)
                true
            }
            is FetchResult.Failure -> false
        }
    }

    /** Insert-then-update keeps positions/downloads intact (EpisodeDao contract). */
    private suspend fun upsertEpisodes(podcastId: String, parsed: ParsedFeed) {
        val entities = parsed.items.map { item ->
            EpisodeEntity(
                id = PodcastIds.episodeId(podcastId, item.guid ?: item.enclosureUrl),
                podcastId = podcastId,
                title = item.title,
                pubDateUtc = item.pubDateUtc,
                durationMs = item.durationMs,
                enclosureUrl = item.enclosureUrl,
                showNotesHtml = item.showNotesHtml,
                transcriptUrl = item.transcriptUrl,
                transcriptType = item.transcriptType,
            )
        }
        episodeDao.insertIgnore(entities)
        entities.forEach { e ->
            episodeDao.updateMetadata(
                id = e.id,
                title = e.title,
                pubDateUtc = e.pubDateUtc,
                enclosureUrl = e.enclosureUrl,
                showNotesHtml = e.showNotesHtml,
                transcriptUrl = e.transcriptUrl,
                transcriptType = e.transcriptType,
                durationMs = e.durationMs,
            )
        }
    }

    /** Best-effort mirror: show.json + cover + latest N episode dirs. Never throws. */
    private suspend fun writeTree(podcast: PodcastEntity) {
        cacheWriter.writeShow(podcast)
        val artworkUrl = podcast.artworkUrl
        if (artworkUrl != null && !cacheWriter.coverExists(podcast)) {
            fetchBytes(artworkUrl)?.let { cacheWriter.writeCover(podcast, it) }
        }
        episodeDao.latestForPodcast(podcast.id, TREE_EPISODE_LIMIT).forEach { episode ->
            cacheWriter.writeEpisode(podcast, episode)
        }
    }

    private fun fetchBytes(url: String): ByteArray? = try {
        client.newCall(Request.Builder().url(url).build()).execute().use { response ->
            if (response.isSuccessful) response.body?.bytes() else null
        }
    } catch (_: Exception) {
        null
    }
}
