package com.orator.feature.audiobooks.data

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.orator.core.database.BookEntity
import com.orator.core.database.ChapterEntity
import com.orator.core.database.OratorDatabase
import com.orator.core.database.SourceKind
import com.orator.core.model.BookDetailResolver
import com.orator.core.model.BookOrigin
import com.orator.core.playback.PlayerPrefs
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class AudiobookPlayPreparerTest {
    private lateinit var db: OratorDatabase

    @Before fun setUp() {
        db = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext(), OratorDatabase::class.java,
        ).allowMainThreadQueries().build()
    }
    @After fun tearDown() = db.close()

    // Fake resolver: an un-resolved ABS book (sourceUri "") gets a real file URI + a chapter,
    // exactly as AbsBookDetailResolver would.
    private fun resolver() = object : BookDetailResolver {
        override fun handles(origin: BookOrigin) = origin == BookOrigin.ABS
        override suspend fun ensureDetails(bookId: String) {
            val b = db.bookDao().getById(bookId) ?: return
            if (b.sourceUri.isNotBlank()) return
            db.chapterDao().replaceForBook(bookId, listOf(
                ChapterEntity(bookId, 0, "Ch1", "https://abs/stream", 0, 60_000),
            ))
            db.bookDao().upsert(listOf(b.copy(sourceUri = "https://abs/stream")))
        }
    }

    private fun preparer() = AudiobookPlayPreparer(db.bookDao(), db.chapterDao(), setOf(resolver()))

    @Test fun `prepare resolves an un-resolved ABS book and builds a non-empty queue`() = runBlocking {
        db.bookDao().upsert(listOf(BookEntity(
            id = "abs:1", title = "B", author = null, coverPath = null,
            sourceUri = "", sourceKind = SourceKind.SINGLE_FILE, durationMs = 60_000,
            addedAtUtc = 0, origin = BookOrigin.ABS, absItemId = "1",
        )))
        val req = preparer().prepare("abs:1", PlayerPrefs())
        // resolved:
        assertTrue(db.bookDao().getById("abs:1")!!.sourceUri.isNotBlank())
        // non-empty queue with a real uri (the P0 was an empty uri):
        assertTrue(req!!.items.isNotEmpty())
        assertEquals("https://abs/stream", req.items.first().uri)
    }
}
