package com.orator.feature.podcasts.work

import android.content.Context
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.orator.feature.podcasts.data.PodcastRepository
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject

/**
 * Background feed refresh. Delegates to [PodcastRepository.refreshAll], which detects new episodes
 * and fires the auto-insert seam. Used for both the periodic job and the one-shot app-open refresh.
 */
@HiltWorker
class FeedRefreshWorker @AssistedInject constructor(
    @Assisted appContext: Context,
    @Assisted params: WorkerParameters,
    private val repository: PodcastRepository,
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result =
        runCatching { repository.refreshAll() }
            .fold(onSuccess = { Result.success() }, onFailure = { Result.retry() })
}
