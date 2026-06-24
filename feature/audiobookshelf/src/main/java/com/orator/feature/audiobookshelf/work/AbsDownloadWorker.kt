package com.orator.feature.audiobookshelf.work

import android.content.Context
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.orator.core.database.BookDao
import com.orator.core.model.DownloadState
import com.orator.feature.audiobookshelf.data.AbsFileDownloader
import com.orator.feature.audiobookshelf.data.AbsPrefs
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject

@HiltWorker
class AbsDownloadWorker @AssistedInject constructor(
    @Assisted context: Context,
    @Assisted params: WorkerParameters,
    private val downloader: AbsFileDownloader,
    private val bookDao: BookDao,
    private val prefs: AbsPrefs,
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val bookId = inputData.getString(KEY_BOOK_ID) ?: return Result.failure()
        // No download folder is a non-transient condition: fail (don't leave a pending retry).
        // The book stays NONE; the settings "Download folder" row guides the user to set one.
        if (prefs.downloadTreeUriNow() == null) return Result.failure()
        bookDao.getById(bookId)?.let {
            bookDao.upsert(listOf(it.copy(downloadState = DownloadState.DOWNLOADING)))
        }
        val ok = runCatching { downloader.download(bookId) }.getOrDefault(false)
        if (!ok) {
            bookDao.getById(bookId)?.let {
                bookDao.upsert(listOf(it.copy(downloadState = DownloadState.NONE)))
            }
            return Result.retry()   // transient (network); WorkManager backs off
        }
        return Result.success()
    }

    companion object { const val KEY_BOOK_ID = "book_id" }
}
