package com.orator.feature.audiobooks.data
import com.orator.core.playback.ids.AudiobookMediaId

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.orator.core.database.OratorDatabase
import com.orator.core.database.BookEntity
import com.orator.core.database.ChapterEntity
import com.orator.core.database.SourceKind
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class AudiobookPositionListenerTest {

    private lateinit var db: OratorDatabase
    private lateinit var listener: AudiobookPositionListener

    @Before
    fun setUp() {
        db = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext(),
            OratorDatabase::class.java,
        ).allowMainThreadQueries().build()
        listener = AudiobookPositionListener(db.bookDao(), db.chapterDao())
    }

    @After
    fun tearDown() {
        db.close()
    }

    private suspend fun seedBook(kind: SourceKind) {
        db.bookDao().upsert(
            listOf(
                BookEntity(
                    id = "b1", title = "B", author = null, coverPath = null,
                    sourceUri = "uri://b", sourceKind = kind, durationMs = 60_000,
                    positionMs = 0, addedAtUtc = 0,
                ),
            ),
        )
        db.chapterDao().upsertAll(
            listOf(
                ChapterEntity("b1", 0, "c0", "uri://f0", 0, 30_000),
                ChapterEntity("b1", 1, "c1", "uri://f1", 0, 30_000),
            ),
        )
    }

    @Test
    fun `m4b position is stored as-is`() = runBlocking {
        seedBook(SourceKind.M4B)

        listener.onPositionChanged(AudiobookMediaId.encode("b1", 0), 42_000, 60_000)

        val book = db.bookDao().getById("b1")!!
        assertEquals(42_000, book.positionMs)
        assertEquals(true, book.lastPlayedAtMs > 0) // ping also stamps the wall clock
    }

    @Test
    fun `mp3 position is offset by preceding files`() = runBlocking {
        seedBook(SourceKind.MP3_DIR)

        listener.onPositionChanged(AudiobookMediaId.encode("b1", 1), 12_000, 30_000)

        assertEquals(42_000, db.bookDao().getById("b1")!!.positionMs)
    }

    @Test
    fun `foreign mediaIds are ignored`() = runBlocking {
        seedBook(SourceKind.M4B)

        listener.onPositionChanged("podcast/xyz/0", 99_000, 0)

        assertEquals(0, db.bookDao().getById("b1")!!.positionMs)
    }

    @Test
    fun `unknown books are ignored without crashing`() = runBlocking {
        listener.onPositionChanged(AudiobookMediaId.encode("ghost", 0), 1_000, 0)
    }
}
