package com.orator.feature.audiobooks.data

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.orator.core.database.BookEntity
import com.orator.core.database.OratorDatabase
import com.orator.core.database.SourceKind
import com.orator.core.model.BookDetailResolver
import com.orator.core.model.BookOrigin
import com.orator.core.model.MediaRef
import com.orator.core.model.MediaType
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class AudiobookPlayRequestResolverTest {
    private lateinit var db: OratorDatabase

    @Before fun setUp() {
        db = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext(), OratorDatabase::class.java,
        ).allowMainThreadQueries().build()
    }

    @After fun tearDown() = db.close()

    @Test fun `create calls the resolver that handles the book origin`() = runBlocking {
        db.bookDao().upsert(
            listOf(
                BookEntity(
                    id = "abs:1", title = "B", author = null, coverPath = null,
                    sourceUri = "content://x", sourceKind = SourceKind.SINGLE_FILE,
                    durationMs = 0, addedAtUtc = 0, origin = BookOrigin.ABS, absItemId = "1",
                ),
            ),
        )
        var resolved: String? = null
        val resolver = object : BookDetailResolver {
            override fun handles(origin: BookOrigin) = origin == BookOrigin.ABS
            override suspend fun ensureDetails(bookId: String) { resolved = bookId }
        }
        val factory = AudiobookPlayRequestFactory(db.bookDao(), db.chapterDao(), setOf(resolver))
        val req = factory.create(MediaRef(MediaType.AUDIOBOOK, "abs:1"))
        assertEquals("abs:1", resolved)
        assertNotNull(req)
    }
}
