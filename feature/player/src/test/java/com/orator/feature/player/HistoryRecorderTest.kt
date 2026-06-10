package com.orator.feature.player

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.orator.core.database.OratorDatabase
import com.orator.core.model.MediaType
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class HistoryRecorderTest {

    private val db = Room.inMemoryDatabaseBuilder(
        ApplicationProvider.getApplicationContext<Context>(),
        OratorDatabase::class.java,
    ).allowMainThreadQueries().build()

    private val recorder = HistoryRecorder(db.historyDao())

    @After
    fun tearDown() = db.close()

    @Test
    fun `start then end writes one closed row`() = runBlocking {
        recorder.onItemStarted("audiobook/b/0", "Book", MediaType.AUDIOBOOK)
        recorder.onItemEnded("audiobook/b/0", positionMs = 0, completed = true)

        val row = db.historyDao().observeRecent(10).first().single()
        assertEquals("Book", row.title)
        assertEquals("AUDIOBOOK", row.mediaType)
        assertEquals(true, row.completed)
        assertEquals(true, row.endedAtUtc != null)
    }

    @Test
    fun `interrupted session stays open`() = runBlocking {
        recorder.onItemStarted("audiobook/b/0", "Book", null)
        val row = db.historyDao().observeRecent(10).first().single()
        assertNull(row.endedAtUtc)
        assertNull(row.mediaType)
    }
}
