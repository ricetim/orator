package com.orator.feature.podcasts.data

import android.content.Context
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.test.core.app.ApplicationProvider
import androidx.work.Configuration
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.testing.SynchronousExecutor
import androidx.work.testing.WorkManagerTestInitHelper
import com.orator.feature.podcasts.data.RefreshScheduler.Companion.REFRESH_NOW_WORK
import com.orator.feature.podcasts.data.RefreshScheduler.Companion.REFRESH_WORK
import kotlinx.coroutines.test.runTest
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class RefreshSchedulerTest {

    @get:Rule val tmp = TemporaryFolder()

    private lateinit var context: Context
    private lateinit var wm: WorkManager
    private lateinit var scheduler: RefreshScheduler

    @Before fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        val config = Configuration.Builder().setExecutor(SynchronousExecutor()).build()
        WorkManagerTestInitHelper.initializeTestWorkManager(context, config)
        wm = WorkManager.getInstance(context)
        // reconcile()/refreshNow() use only the context; prefs is unused here but required by the ctor.
        val prefs = RefreshPreferences(
            PreferenceDataStoreFactory.create { tmp.newFile("refresh.preferences_pb") },
        )
        scheduler = RefreshScheduler(context, prefs)
    }

    private fun infos(name: String): List<WorkInfo> = wm.getWorkInfosForUniqueWork(name).get()

    @Test fun `reconcile with a positive interval enqueues one periodic work`() = runTest {
        scheduler.reconcile(360)

        val work = infos(REFRESH_WORK)
        assertEquals(1, work.size)
        assertTrue(work.single().state == WorkInfo.State.ENQUEUED)
    }

    @Test fun `reconcile with 0 cancels the periodic work`() = runTest {
        scheduler.reconcile(360)
        scheduler.reconcile(0)

        val work = infos(REFRESH_WORK)
        // Cancelled (or none). UPDATE policy keeps a single unique entry; it must not be ENQUEUED.
        assertTrue(work.isEmpty() || work.all { it.state == WorkInfo.State.CANCELLED })
    }

    @Test fun `changing the interval replaces, not duplicates`() = runTest {
        scheduler.reconcile(360)
        scheduler.reconcile(60)

        assertEquals(1, infos(REFRESH_WORK).size)
    }

    @Test fun `refreshNow enqueues a one-shot refresh`() = runTest {
        scheduler.refreshNow()

        assertEquals(1, infos(REFRESH_NOW_WORK).size)
    }
}
