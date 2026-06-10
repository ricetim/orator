package com.orator.feature.audiobooks.data

import android.content.Context
import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import com.orator.core.database.BookDao
import com.orator.core.database.BookEntity
import com.orator.core.database.BookmarkDao
import com.orator.core.database.BookmarkEntity
import com.orator.core.database.ChapterDao
import com.orator.core.database.ChapterEntity
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

/** The feature's single data entry point: library queries, rescans, positions, bookmarks. */
@Singleton
class AudiobookRepository @Inject constructor(
    @ApplicationContext private val context: Context,
    private val bookDao: BookDao,
    private val chapterDao: ChapterDao,
    private val bookmarkDao: BookmarkDao,
    private val importer: AudiobookImporter,
    private val prefs: AudiobooksPrefs,
) {
    val treeUri: Flow<String?> = prefs.treeUri

    fun observeBooks(): Flow<List<BookEntity>> = bookDao.observeAll()
    fun observeBook(id: String): Flow<BookEntity?> = bookDao.observeById(id)
    fun observeChapters(bookId: String): Flow<List<ChapterEntity>> = chapterDao.observeForBook(bookId)
    fun observeBookmarks(bookId: String): Flow<List<BookmarkEntity>> = bookmarkDao.observeForBook(bookId)

    suspend fun setFolderAndRescan(treeUri: String) {
        prefs.setTreeUri(treeUri)
        rescan()
    }

    /** Re-walks the granted folder and reconciles the library. No-op if no folder chosen yet. */
    suspend fun rescan() = withContext(Dispatchers.IO) {
        val uri = prefs.treeUri.first() ?: return@withContext
        val root = DocumentFile.fromTreeUri(context, Uri.parse(uri)) ?: return@withContext
        importer.import(AudiobookScanner.scan(DocumentFileNode(root)))
    }

    suspend fun chaptersFor(bookId: String): List<ChapterEntity> = chapterDao.getForBook(bookId)

    suspend fun addBookmark(bookId: String, globalPositionMs: Long) {
        bookmarkDao.insert(
            BookmarkEntity(
                bookId = bookId,
                positionMs = globalPositionMs,
                note = null,
                createdAtUtc = System.currentTimeMillis(),
            ),
        )
    }

    suspend fun deleteBookmark(id: Long) = bookmarkDao.delete(id)
}
