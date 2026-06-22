package com.orator.feature.podcasts.data

import android.content.Context
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import com.orator.feature.podcasts.work.FeedRefreshWorker
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Reconciles the [RefreshPreferences] interval into a unique periodic WorkManager job, and runs a
 * one-shot refresh on app open. Depends only on context + prefs; the actual refresh work lives in
 * [FeedRefreshWorker]. Started eagerly from PodcastsFeatureEntry.
 */
@Singleton
class RefreshScheduler @Inject constructor(
    @ApplicationContext private val context: Context,
    private val preferences: RefreshPreferences,
) {
    private val networkConstraint =
        Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()

    /** 0 (or less) → cancel background refresh; else (re)schedule a unique periodic job. */
    fun reconcile(intervalMinutes: Int) {
        val wm = WorkManager.getInstance(context)
        if (intervalMinutes <= 0) {
            wm.cancelUniqueWork(REFRESH_WORK)
            return
        }
        val request = PeriodicWorkRequestBuilder<FeedRefreshWorker>(
            intervalMinutes.toLong(), TimeUnit.MINUTES,
        ).setConstraints(networkConstraint).build()
        wm.enqueueUniquePeriodicWork(REFRESH_WORK, ExistingPeriodicWorkPolicy.UPDATE, request)
    }

    /** One-shot refresh on app open (network-gated). KEEP so repeated cold starts don't stack. */
    fun refreshNow() {
        val request = OneTimeWorkRequestBuilder<FeedRefreshWorker>()
            .setConstraints(networkConstraint).build()
        WorkManager.getInstance(context)
            .enqueueUniqueWork(REFRESH_NOW_WORK, ExistingWorkPolicy.KEEP, request)
    }

    private val started = AtomicBoolean(false)

    /**
     * Production wiring: collect the interval pref (reconcile on change) + one app-open refresh.
     * Idempotent — PodcastsFeatureEntry is reconstructed on every Activity recreation, so guard
     * against launching a duplicate forever-collector each time.
     */
    fun start(scope: CoroutineScope) {
        if (!started.compareAndSet(false, true)) return
        scope.launch { preferences.intervalMinutes.collect { reconcile(it) } }
        refreshNow()
    }

    companion object {
        const val REFRESH_WORK = "feed-refresh"
        const val REFRESH_NOW_WORK = "feed-refresh-now"
    }
}
