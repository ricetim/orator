package com.orator.feature.audiobooks.data

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.orator.core.database.BookEntity
import com.orator.core.database.OratorDatabase
import com.orator.core.database.SourceKind
import com.orator.core.model.MediaRef
import com.orator.core.model.MediaType
import com.orator.core.playback.ids.AudiobookMediaId
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
class AudiobookPlaylistContributionsTest {

    private val db = Room.inMemoryDatabaseBuilder(
        ApplicationProvider.getApplicationContext<Context>(),
        OratorDatabase::class.java,
    ).allowMainThreadQueries().build()

    private val factory = AudiobookPlayRequestFactory(db.bookDao(), db.chapterDao())
    private val resolver = AudiobookPlaylistItemResolver(db.bookDao())

    @After fun tearDown() = db.close()

    private fun book(id: String) = BookEntity(
        id = id, title = "Title $id", author = "Author $id", coverPath = "content://cover/$id",
        sourceUri = "content://audio/$id", sourceKind = SourceKind.SINGLE_FILE,
        durationMs = 1_000, positionMs = 500, addedAtUtc = 0,
    )

    @Test fun `factory returns null for an unknown book`() = runBlocking {
        assertNull(factory.create(MediaRef(MediaType.AUDIOBOOK, "missing")))
    }

    @Test fun `factory builds a resume PlayRequest for a known book`() = runBlocking {
        db.bookDao().upsert(listOf(book("b1")))

        val req = factory.create(MediaRef(MediaType.AUDIOBOOK, "b1"))!!

        assertEquals(MediaType.AUDIOBOOK, req.mediaType)
        assertEquals(500L, req.startPositionMs) // resumes at the book's saved position
        assertEquals(AudiobookMediaId.encode("b1", 0), req.items.first().mediaId)
        assertEquals("content://audio/b1", req.items.first().uri)
    }

    @Test fun `resolver maps a known book to display content`() = runBlocking {
        db.bookDao().upsert(listOf(book("b1")))

        val content = resolver.resolve(MediaRef(MediaType.AUDIOBOOK, "b1"))!!

        assertEquals("Title b1", content.title)
        assertEquals("Author b1", content.subtitle)
        assertEquals("content://cover/b1", content.artworkUri)
        assertEquals(1_000L, content.durationMs)
    }

    @Test fun `resolver returns null for an unknown book`() = runBlocking {
        assertNull(resolver.resolve(MediaRef(MediaType.AUDIOBOOK, "missing")))
    }
}
