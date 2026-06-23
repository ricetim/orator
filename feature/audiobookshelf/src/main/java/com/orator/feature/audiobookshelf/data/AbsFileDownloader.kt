package com.orator.feature.audiobookshelf.data

import android.content.Context
import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import com.orator.core.database.BookDao
import com.orator.core.database.ChapterDao
import com.orator.core.model.DownloadState
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import javax.inject.Inject

/**
 * Downloads an ABS book's audio tracks into the user's SAF download folder, then rewrites the book
 * to play from the local content:// URIs. Mirrors EpisodeDownloader's .partial→rename discipline so
 * an interrupted download never masquerades as complete. Authed via AbsAuthInterceptor (ABS host).
 */
class AbsFileDownloader @Inject constructor(
    @ApplicationContext private val context: Context,
    private val client: OkHttpClient,
    private val prefs: AbsPrefs,
    private val bookDao: BookDao,
    private val chapterDao: ChapterDao,
    private val detailResolver: AbsBookDetailResolver,
) {
    /** @return true on success. Caller (worker) manages DOWNLOADING/NONE state transitions. */
    suspend fun download(bookId: String): Boolean = withContext(Dispatchers.IO) {
        detailResolver.ensureDetails(bookId)                       // guarantees sourceUri + chapters
        val book = bookDao.getById(bookId) ?: return@withContext false
        if (book.sourceUri.isBlank()) return@withContext false
        val chapters = chapterDao.getForBook(bookId)
        val treeUri = prefs.downloadTreeUriNow() ?: return@withContext false
        val tree = DocumentFile.fromTreeUri(context, Uri.parse(treeUri)) ?: return@withContext false
        val bookDir = tree.findFile("abs-$bookId")?.takeIf { it.isDirectory }
            ?: tree.createDirectory("abs-$bookId") ?: return@withContext false

        val plan = AbsDownloadPlan.from(book.sourceUri, chapters)
        val localByRemote = mutableMapOf<String, String>()
        for (file in plan.files) {
            val dest = downloadOne(file.remoteUrl, bookDir, file.localName) ?: return@withContext false
            localByRemote[file.remoteUrl] = dest
        }
        val rewrite = plan.rewrite(chapters, book.sourceUri, localByRemote)
        chapterDao.replaceForBook(bookId, rewrite.chapters)
        bookDao.upsert(listOf(book.copy(sourceUri = rewrite.sourceUri, downloadState = DownloadState.DOWNLOADED)))
        true
    }

    private fun downloadOne(url: String, dir: DocumentFile, name: String): String? {
        dir.findFile("$name.partial")?.delete()
        val partial = dir.createFile("application/octet-stream", "$name.partial") ?: return null
        return try {
            client.newCall(Request.Builder().url(url).build()).execute().use { resp ->
                if (!resp.isSuccessful) { partial.delete(); return null }
                context.contentResolver.openOutputStream(partial.uri, "wt")!!.use { out ->
                    resp.body!!.byteStream().use { it.copyTo(out, 64 * 1024) }
                }
            }
            if (!partial.renameTo(name)) { partial.delete(); return null }
            dir.findFile(name)?.uri?.toString()
        } catch (e: Exception) {
            partial.delete()
            null
        }
    }

    suspend fun deleteFiles(bookId: String) = withContext(Dispatchers.IO) {
        val book = bookDao.getById(bookId) ?: return@withContext
        val uris = (listOf(book.sourceUri) + chapterDao.getForBook(bookId).map { it.fileUri })
            .filter { it.startsWith("content://") }.distinct()
        uris.forEach { runCatching { DocumentFile.fromSingleUri(context, Uri.parse(it))?.delete() } }
    }

    /** Remove a download: delete files, clear chapters, revert to stream-only (next play re-resolves). */
    suspend fun removeDownload(bookId: String) = withContext(Dispatchers.IO) {
        deleteFiles(bookId)
        chapterDao.replaceForBook(bookId, emptyList())
        bookDao.getById(bookId)?.let {
            bookDao.upsert(listOf(it.copy(sourceUri = "", downloadState = DownloadState.NONE)))
        }
    }
}
