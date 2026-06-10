package com.orator.feature.audiobooks.data

import android.net.Uri
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.orator.core.database.OratorDatabase
import com.orator.core.database.SourceKind
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class AudiobookImporterTest {

    private lateinit var db: OratorDatabase
    private lateinit var importer: AudiobookImporter

    private val fakeExtractor = object : AudiobookMetadataExtractor {
        override fun extract(uri: Uri) = ExtractedMetadata(
            title = "Tagged Title",
            author = "Tagged Author",
            durationMs = 60_000,
            coverBytes = null,
        )
    }

    private var chaptersInM4b: List<Mp4ChapterParser.Chapter> = emptyList()
    private val fakeChapterSource = object : M4bChapterSource {
        override fun chaptersOf(uri: Uri) = chaptersInM4b
    }

    @Before
    fun setUp() {
        db = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext(),
            OratorDatabase::class.java,
        ).allowMainThreadQueries().build()
        importer = AudiobookImporter(
            bookDao = db.bookDao(),
            chapterDao = db.chapterDao(),
            extractor = fakeExtractor,
            chapterSource = fakeChapterSource,
            coverStore = CoverStore(ApplicationProvider.getApplicationContext()),
        )
    }

    @After
    fun tearDown() {
        db.close()
    }

    @Test
    fun `imports an m4b with chpl chapters`() = runBlocking {
        chaptersInM4b = listOf(
            Mp4ChapterParser.Chapter("One", 0),
            Mp4ChapterParser.Chapter("Two", 30_000),
        )

        importer.import(listOf(ScannedBook.M4b("File Name", "uri://book1")))

        val book = db.bookDao().observeAll().first().single()
        assertEquals("Tagged Title", book.title)
        assertEquals(SourceKind.M4B, book.sourceKind)
        assertEquals(60_000, book.durationMs)

        val chapters = db.chapterDao().getForBook(book.id)
        assertEquals(listOf("One", "Two"), chapters.map { it.title })
        assertEquals(listOf(0L, 30_000L), chapters.map { it.startMs })
        // last chapter runs to end of file
        assertEquals(listOf(30_000L, 30_000L), chapters.map { it.durationMs })
        assertTrue(chapters.all { it.fileUri == "uri://book1" })
    }

    @Test
    fun `m4b without chapters gets one full-length chapter`() = runBlocking {
        chaptersInM4b = emptyList()

        importer.import(listOf(ScannedBook.M4b("File Name", "uri://book1")))

        val book = db.bookDao().observeAll().first().single()
        val chapter = db.chapterDao().getForBook(book.id).single()
        assertEquals(0L, chapter.startMs)
        assertEquals(60_000L, chapter.durationMs)
    }

    @Test
    fun `imports an mp3 collection with one chapter per file`() = runBlocking {
        val scanned = ScannedBook.Mp3Collection(
            title = "Dir Name",
            rootUri = "uri://dir",
            files = listOf(ScannedFile("01 Intro.mp3", "uri://f1"), ScannedFile("02 Body.mp3", "uri://f2")),
        )

        importer.import(listOf(scanned))

        val book = db.bookDao().observeAll().first().single()
        assertEquals(SourceKind.MP3_DIR, book.sourceKind)
        assertEquals(120_000, book.durationMs) // 2 files x fake 60s

        val chapters = db.chapterDao().getForBook(book.id)
        assertEquals(listOf("01 Intro", "02 Body"), chapters.map { it.title })
        assertEquals(listOf("uri://f1", "uri://f2"), chapters.map { it.fileUri })
        assertTrue(chapters.all { it.startMs == 0L })
    }

    @Test
    fun `rescan keeps existing books and their positions`() = runBlocking {
        importer.import(listOf(ScannedBook.M4b("Book", "uri://book1")))
        val id = db.bookDao().observeAll().first().single().id
        db.bookDao().updateProgress(id, 42_000, lastPlayedAtMs = 1)

        importer.import(listOf(ScannedBook.M4b("Book", "uri://book1")))

        val book = db.bookDao().observeAll().first().single()
        assertEquals(42_000, book.positionMs)
    }

    @Test
    fun `books that vanished from disk are removed`() = runBlocking {
        importer.import(listOf(ScannedBook.M4b("Book", "uri://book1")))

        importer.import(emptyList())

        assertTrue(db.bookDao().observeAll().first().isEmpty())
    }
}
