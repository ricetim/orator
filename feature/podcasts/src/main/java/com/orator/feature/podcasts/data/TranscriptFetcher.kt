package com.orator.feature.podcasts.data

import com.orator.core.database.EpisodeDao
import com.orator.core.database.PodcastDao
import com.orator.feature.podcasts.data.search.SEARCH_USER_AGENT
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Downloads an episode's Podcasting-2.0 transcript into its cache-tree dir and records the
 * path. Called automatically after a successful audio download and on demand from the episode
 * screen. Failures never affect the audio download result.
 */
@Singleton
class TranscriptFetcher @Inject constructor(
    private val client: OkHttpClient,
    private val podcastDao: PodcastDao,
    private val episodeDao: EpisodeDao,
    private val cacheWriter: EpisodeCacheWriter,
) {

    /** Outcome of the most recent attempt, for the episode screen's status line. */
    private val _lastEvent = MutableStateFlow<String?>(null)
    val lastEvent: StateFlow<String?> = _lastEvent.asStateFlow()

    /** Auto path: silently no-ops when there is nothing to fetch or it is already fetched. */
    suspend fun fetchIfAvailable(episodeId: String) {
        val episode = episodeDao.getById(episodeId) ?: return
        if (episode.transcriptUrl == null || episode.transcriptPath != null) return
        fetch(episodeId)
    }

    suspend fun fetch(episodeId: String): Result<Unit> = withContext(Dispatchers.IO) {
        val result = runCatching {
            val episode = episodeDao.getById(episodeId)
                ?: error("unknown episode")
            val url = episode.transcriptUrl ?: error("episode has no transcript")
            val podcast = podcastDao.getById(episode.podcastId) ?: error("unknown podcast")

            val bytes = client.newCall(
                Request.Builder().url(url).header("User-Agent", SEARCH_USER_AGENT).build(),
            ).execute().use { response ->
                check(response.isSuccessful) { "HTTP ${response.code}" }
                response.body?.bytes() ?: error("empty body")
            }

            val name = "transcript.${transcriptExt(episode.transcriptType, url)}"
            val file = cacheWriter.writeEpisodeFile(podcast, episode, name, bytes)
                ?: error("no cache folder granted")
            episodeDao.updateTranscriptPath(episodeId, file.uri.toString())
        }
        _lastEvent.value = result.fold(
            onSuccess = { "Transcript saved" },
            onFailure = { "Transcript failed: ${it.message}" },
        )
        result
    }

    companion object {
        fun transcriptExt(type: String?, url: String): String {
            when {
                type == null -> Unit
                type.contains("vtt") -> return "vtt"
                type.contains("srt") || type.contains("subrip") -> return "srt"
                type.contains("json") -> return "json"
                type.contains("text/plain") -> return "txt"
            }
            val ext = url.substringBefore('?').substringAfterLast('/')
                .substringAfterLast('.', missingDelimiterValue = "")
            return if (ext in setOf("vtt", "srt", "json", "txt")) ext else "txt"
        }
    }
}
