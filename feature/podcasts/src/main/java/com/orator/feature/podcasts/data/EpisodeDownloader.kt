package com.orator.feature.podcasts.data

import android.content.Context
import android.net.Uri
import android.util.Log
import androidx.documentfile.provider.DocumentFile
import com.orator.core.database.EpisodeDao
import com.orator.core.database.PodcastDao
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Explicit per-episode downloads, one at a time (Mutex). Streams to "audio.partial" then renames,
 * so an interrupted download never masquerades as a finished file; a stale partial from a killed
 * app is deleted at the next attempt. Progress is -1 while indeterminate (no Content-Length).
 * UI calls [enqueue] — downloads run in the singleton's own scope so navigating away from the
 * episode screen doesn't cancel them. Cancel is checked between 64 KB reads; a stalled stream
 * is ended by the client's read timeout.
 */
@Singleton
class EpisodeDownloader @Inject constructor(
    @ApplicationContext private val context: Context,
    private val client: OkHttpClient,
    private val podcastDao: PodcastDao,
    private val episodeDao: EpisodeDao,
    private val cacheWriter: EpisodeCacheWriter,
    private val transcriptFetcher: TranscriptFetcher,
) {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val mutex = Mutex()
    private val _progress = MutableStateFlow<Map<String, Float>>(emptyMap())
    val progress: StateFlow<Map<String, Float>> = _progress.asStateFlow()

    /** Outcome of the most recent download attempt, for the placeholder UI's status line. */
    private val _lastEvent = MutableStateFlow<String?>(null)
    val lastEvent: StateFlow<String?> = _lastEvent.asStateFlow()

    @Volatile private var cancelled: String? = null

    /** Fire-and-forget entry point for the UI; survives the caller's lifecycle. */
    fun enqueue(episodeId: String) {
        // Indeterminate progress immediately: connection setup (redirects + TLS) takes seconds
        // before the first chunk arrives, and the button must not look dead in the meantime.
        setProgress(episodeId, -1f)
        scope.launch {
            _lastEvent.value = null
            _lastEvent.value = download(episodeId).fold(
                onSuccess = {
                    // Deliberate ordering: "Download complete" publishes only AFTER the
                    // transcript fetch finishes — transcripts are small and the combined
                    // "done" is the honest signal.
                    transcriptFetcher.fetchIfAvailable(episodeId)
                    "Download complete"
                },
                onFailure = { e ->
                    Log.w(TAG, "download failed for $episodeId", e)
                    "Download failed: ${e.message}"
                },
            )
        }
    }

    fun cancel(episodeId: String) {
        cancelled = episodeId
    }

    suspend fun download(episodeId: String): Result<Unit> = mutex.withLock {
        withContext(Dispatchers.IO) {
            cancelled = null
            val episode = episodeDao.getById(episodeId)
                ?: return@withContext Result.failure(IllegalArgumentException("unknown episode"))
            val podcast = podcastDao.getById(episode.podcastId)
                ?: return@withContext Result.failure(IllegalArgumentException("unknown podcast"))
            val dir = cacheWriter.episodeDir(podcast, episode, create = true)
                ?: return@withContext Result.failure(IllegalStateException("no cache folder granted"))

            // also mirror metadata for downloaded episodes regardless of the latest-N window
            cacheWriter.writeEpisode(podcast, episode)

            dir.findFile("audio.partial")?.delete()
            val partial = dir.createFile("application/octet-stream", "audio.partial")
                ?: return@withContext Result.failure(IllegalStateException("cannot create file"))
            // renameTo mutates the DocumentFile's URI in place: after a successful rename,
            // `partial` POINTS AT THE FINISHED FILE — the catch blocks must not delete it then.
            var renamed = false
            // Servers sometimes abort the connection AFTER the last byte; once the file is
            // renamed and recorded, a close-time exception must not report failure.
            var succeeded = false

            try {
                client.newCall(Request.Builder().url(episode.enclosureUrl).build())
                    .execute().use { response ->
                        if (!response.isSuccessful) {
                            partial.delete()
                            return@withContext Result.failure(IllegalStateException("HTTP ${response.code}"))
                        }
                        val body = response.body
                            ?: return@withContext Result.failure(IllegalStateException("empty body"))
                        val total = body.contentLength()
                        val ext = audioExt(response.header("Content-Type"), episode.enclosureUrl)
                        var copied = 0L
                        context.contentResolver.openOutputStream(partial.uri, "wt")!!.use { out ->
                            body.byteStream().use { input ->
                                val buffer = ByteArray(64 * 1024)
                                while (true) {
                                    if (cancelled == episodeId) {
                                        partial.delete()
                                        return@withContext Result.failure(InterruptedException("cancelled"))
                                    }
                                    val read = input.read(buffer)
                                    if (read < 0) break
                                    out.write(buffer, 0, read)
                                    copied += read
                                    setProgress(episodeId, if (total > 0) copied.toFloat() / total else -1f)
                                }
                            }
                        }
                        if (!partial.renameTo("audio.$ext")) {
                            partial.delete()
                            return@withContext Result.failure(IllegalStateException("rename failed"))
                        }
                        renamed = true
                        val finalFile = dir.findFile("audio.$ext")
                            ?: return@withContext Result.failure(IllegalStateException("file vanished"))
                        episodeDao.updateAudioPath(episodeId, finalFile.uri.toString())
                        succeeded = true
                        Result.success(Unit)
                    }
            } catch (e: CancellationException) {
                if (succeeded) return@withContext Result.success(Unit)
                if (!renamed) partial.delete()
                throw e
            } catch (e: Exception) {
                if (succeeded) return@withContext Result.success(Unit)
                if (!renamed) partial.delete()
                Result.failure(e)
            } finally {
                setProgress(episodeId, null)
            }
        }
    }

    suspend fun deleteDownload(episodeId: String) = withContext(Dispatchers.IO) {
        val episode = episodeDao.getById(episodeId) ?: return@withContext
        episode.audioPath?.let { path ->
            runCatching {
                DocumentFile.fromSingleUri(context, Uri.parse(path))?.delete()
            }
        }
        episodeDao.updateAudioPath(episodeId, null)
    }

    private fun setProgress(episodeId: String, value: Float?) {
        _progress.value = if (value == null) {
            _progress.value - episodeId
        } else {
            _progress.value + (episodeId to value)
        }
    }

    companion object {
        private const val TAG = "EpisodeDownloader"

        fun audioExt(contentType: String?, url: String): String {
            when {
                contentType == null -> Unit
                contentType.startsWith("audio/mpeg") -> return "mp3"
                contentType.startsWith("audio/mp4") || contentType.contains("m4a") -> return "m4a"
                contentType.startsWith("audio/ogg") -> return "ogg"
            }
            val path = url.substringBefore('?').substringAfterLast('/')
            val ext = path.substringAfterLast('.', missingDelimiterValue = "")
            return if (ext in setOf("mp3", "m4a", "m4b", "ogg", "opus", "aac", "wav")) ext else "mp3"
        }
    }
}
