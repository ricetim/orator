package com.orator.feature.audiobookshelf.work

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.work.Configuration
import androidx.work.WorkManager
import androidx.work.testing.SynchronousExecutor
import androidx.work.testing.WorkManagerTestInitHelper
import com.orator.feature.audiobookshelf.data.AbsDownloadManager
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class AbsDownloadWorkerTest {
    private lateinit var context: Context

    @Before fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        WorkManagerTestInitHelper.initializeTestWorkManager(
            context, Configuration.Builder().setExecutor(SynchronousExecutor()).build(),
        )
    }

    @Test fun `enqueue schedules one unique work per book`() {
        val manager = AbsDownloadManager(context)
        manager.enqueue("abs:1")
        manager.enqueue("abs:1")   // re-tap: KEEP keeps a single entry
        val wm = WorkManager.getInstance(context)
        assertEquals(1, wm.getWorkInfosForUniqueWork("abs-download-abs:1").get().size)
    }
}
