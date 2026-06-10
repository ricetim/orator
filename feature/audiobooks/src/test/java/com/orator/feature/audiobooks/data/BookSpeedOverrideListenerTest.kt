package com.orator.feature.audiobooks.data

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.orator.core.database.BookEntity
import com.orator.core.database.OratorDatabase
import com.orator.core.database.SourceKind
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class BookSpeedOverrideListenerTest {

    private lateinit var db: OratorDatabase
    private lateinit var listener: BookSpeedOverrideListener

    @Before
    fun setUp() {
        db = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext(),
            OratorDatabase::class.java,
        ).allowMainThreadQueries().build()
        listener = BookSpeedOverrideListener(db.bookDao())
        runBlocking {
            db.bookDao().upsert(
                listOf(
                    BookEntity(
                        id = "b1", title = "B", author = null, coverPath = null,
                        sourceUri = "uri://b", sourceKind = SourceKind.M4B, durationMs = 60_000,
                        positionMs = 0, addedAtUtc = 0,
                    ),
                ),
            )
        }
    }

    @After
    fun tearDown() {
        db.close()
    }

    @Test
    fun `persists and clears the override for the owning book`() = runBlocking {
        listener.onSpeedOverrideChanged(AudiobookMediaId.encode("b1", 0), 1.4f)
        assertEquals(1.4f, db.bookDao().getById("b1")?.speedOverride)

        listener.onSpeedOverrideChanged(AudiobookMediaId.encode("b1", 0), null)
        assertEquals(null, db.bookDao().getById("b1")?.speedOverride)
    }

    @Test
    fun `ignores non-audiobook mediaIds`() = runBlocking {
        listener.onSpeedOverrideChanged("podcast/x/3", 2.0f) // must not throw
        assertEquals(null, db.bookDao().getById("b1")?.speedOverride)
    }
}
