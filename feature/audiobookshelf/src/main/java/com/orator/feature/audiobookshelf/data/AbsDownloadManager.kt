package com.orator.feature.audiobookshelf.data

import android.content.Context
import androidx.work.Constraints
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.workDataOf
import com.orator.feature.audiobookshelf.work.AbsDownloadWorker
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/** Context-only: schedules/cancels the download worker. File deletion lives on AbsFileDownloader. */
@Singleton
class AbsDownloadManager @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    fun enqueue(bookId: String) {
        val req = OneTimeWorkRequestBuilder<AbsDownloadWorker>()
            .setInputData(workDataOf(AbsDownloadWorker.KEY_BOOK_ID to bookId))
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
            .build()
        WorkManager.getInstance(context)
            .enqueueUniqueWork(workName(bookId), ExistingWorkPolicy.KEEP, req)
    }

    fun cancel(bookId: String) {
        WorkManager.getInstance(context).cancelUniqueWork(workName(bookId))
    }

    private fun workName(bookId: String) = "abs-download-$bookId"
}
