package com.orator.feature.podcasts.data

import android.content.Context
import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import com.orator.core.database.EpisodeEntity
import com.orator.core.database.PodcastEntity
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Writes the human-readable mirror: Podcasts/<Show>/show.json + cover + episodes/<date - title>/.
 * Per the spec amendment, episode dirs are only written for the latest N per show plus downloaded
 * episodes — SAF ops cost 10–50 ms each, so a full-history import must not touch the tree.
 * Every method is best-effort: tree failures never block DB writes (the DB is the source of truth).
 */
@Singleton
class EpisodeCacheWriter @Inject constructor(
    @ApplicationContext private val context: Context,
    private val folderStore: PodcastsFolderStore,
) {

    /** Resolves <picked>/Podcasts, creating it if needed; null when no folder is granted yet. */
    private suspend fun podcastsRoot(): DocumentFile? {
        val uri = folderStore.treeUri.first() ?: return null
        val base = DocumentFile.fromTreeUri(context, Uri.parse(uri)) ?: return null
        return base.findFile("Podcasts")?.takeIf { it.isDirectory }
            ?: base.createDirectory("Podcasts")
    }

    /**
     * Show dir named by sanitized title; on collision with a DIFFERENT show (marker file
     * `.orator-id` holds the owning podcast id) the name gets an id suffix.
     */
    private suspend fun showDir(podcast: PodcastEntity, create: Boolean): DocumentFile? {
        val root = podcastsRoot() ?: return null
        val plain = CacheNames.sanitize(podcast.title)
        for (name in listOf(plain, CacheNames.withIdSuffix(plain, podcast.id))) {
            val existing = root.findFile(name)
            if (existing == null) {
                if (!create) return null
                val dir = root.createDirectory(name) ?: return null
                writeText(dir, ".orator-id", podcast.id)
                return dir
            }
            if (ownerId(existing) == podcast.id) return existing
        }
        return null
    }

    private fun ownerId(dir: DocumentFile): String? =
        dir.findFile(".orator-id")?.let { f ->
            runCatching {
                context.contentResolver.openInputStream(f.uri)?.use {
                    it.readBytes().decodeToString().trim()
                }
            }.getOrNull()
        }

    suspend fun writeShow(podcast: PodcastEntity) = bestEffort {
        val dir = showDir(podcast, create = true) ?: return@bestEffort
        writeText(dir, "show.json", CacheJson.showJson(podcast))
    }

    suspend fun writeCover(podcast: PodcastEntity, bytes: ByteArray) = bestEffort {
        val dir = showDir(podcast, create = true) ?: return@bestEffort
        writeBytes(dir, "cover.jpg", bytes)
    }

    /** Covers rarely change; refresh skips the re-download when one is already on disk. */
    suspend fun coverExists(podcast: PodcastEntity): Boolean = try {
        showDir(podcast, create = false)?.findFile("cover.jpg") != null
    } catch (_: Exception) {
        false
    }

    suspend fun writeEpisode(podcast: PodcastEntity, episode: EpisodeEntity) = bestEffort {
        episodeDir(podcast, episode, create = true)?.let { dir ->
            writeText(dir, "episode.json", CacheJson.episodeJson(episode))
            episode.showNotesHtml?.let { writeText(dir, "shownotes.html", it) }
        }
    }

    /** Used by the downloader to place audio files. */
    suspend fun episodeDir(
        podcast: PodcastEntity,
        episode: EpisodeEntity,
        create: Boolean,
    ): DocumentFile? {
        val show = showDir(podcast, create) ?: return null
        val episodes = show.findFile("episodes")?.takeIf { it.isDirectory }
            ?: if (create) show.createDirectory("episodes") else null
        val episodesDir = episodes ?: return null
        val name = CacheNames.episodeDirName(episode.pubDateUtc, episode.title)
        for (candidate in listOf(name, CacheNames.withIdSuffix(name, episode.id))) {
            val existing = episodesDir.findFile(candidate)
            if (existing == null) {
                if (!create) return null
                val dir = episodesDir.createDirectory(candidate) ?: return null
                writeText(dir, ".orator-id", episode.id)
                return dir
            }
            if (ownerId(existing) == episode.id) return existing
        }
        return null
    }

    private suspend fun writeText(dir: DocumentFile, name: String, content: String) =
        writeBytes(dir, name, content.toByteArray())

    private suspend fun writeBytes(dir: DocumentFile, name: String, bytes: ByteArray) {
        withContext(Dispatchers.IO) {
            val mime = if (name.endsWith(".jpg")) "image/jpeg" else "text/plain"
            val file = dir.findFile(name) ?: dir.createFile(mime, name) ?: return@withContext
            context.contentResolver.openOutputStream(file.uri, "wt")?.use { it.write(bytes) }
        }
    }

    private suspend fun bestEffort(block: suspend () -> Unit) {
        try {
            block()
        } catch (_: Exception) {
            // Tree is a mirror; a failed write must never fail the refresh.
        }
    }
}
