package com.orator.feature.audiobooks.data

import android.net.Uri
import com.orator.core.database.BookDao
import com.orator.core.database.BookEntity
import com.orator.core.database.ChapterDao
import com.orator.core.database.ChapterEntity
import com.orator.core.database.SourceKind
import javax.inject.Inject

/**
 * Reconciles a scan result with the library: new books get metadata extracted and chapter
 * rows built; existing books are left untouched (preserving position/bookmarks); books no
 * longer on disk are deleted (cascades to chapters + bookmarks).
 */
class AudiobookImporter @Inject constructor(
    private val bookDao: BookDao,
    private val chapterDao: ChapterDao,
    private val extractor: AudiobookMetadataExtractor,
    private val chapterSource: M4bChapterSource,
    private val coverStore: CoverStore,
) {

    suspend fun import(scanned: List<ScannedBook>) {
        val scannedById = scanned.associateBy { BookIds.fromUri(it.rootUri) }
        val existingIds = bookDao.getAllIds().toSet()

        val vanished = existingIds - scannedById.keys
        if (vanished.isNotEmpty()) bookDao.deleteByIds(vanished.toList())

        scannedById
            .filterKeys { it !in existingIds }
            .forEach { (id, book) -> importNew(id, book) }
    }

    private suspend fun importNew(id: String, book: ScannedBook) {
        when (book) {
            is ScannedBook.M4b -> importM4b(id, book)
            is ScannedBook.Mp3Collection -> importMp3Collection(id, book)
        }
    }

    private suspend fun importM4b(id: String, book: ScannedBook.M4b) {
        val uri = Uri.parse(book.rootUri)
        val meta = extractor.extract(uri)
        val marks = chapterSource.chaptersOf(uri)
            .ifEmpty { listOf(Mp4ChapterParser.Chapter(title = book.title, startMs = 0)) }

        val chapters = marks.mapIndexed { index, mark ->
            val end = marks.getOrNull(index + 1)?.startMs ?: meta.durationMs
            ChapterEntity(
                bookId = id,
                chapterIndex = index,
                title = mark.title,
                fileUri = book.rootUri,
                startMs = mark.startMs,
                durationMs = (end - mark.startMs).coerceAtLeast(0),
            )
        }
        insert(id, book, SourceKind.M4B, meta, durationMs = meta.durationMs, chapters = chapters)
    }

    private suspend fun importMp3Collection(id: String, book: ScannedBook.Mp3Collection) {
        var firstFileMeta: ExtractedMetadata? = null
        val chapters = book.files.mapIndexed { index, file ->
            val fileMeta = extractor.extract(Uri.parse(file.uri))
            if (firstFileMeta == null) firstFileMeta = fileMeta
            ChapterEntity(
                bookId = id,
                chapterIndex = index,
                title = file.name.substringBeforeLast('.'),
                fileUri = file.uri,
                startMs = 0,
                durationMs = fileMeta.durationMs,
            )
        }
        val first = firstFileMeta ?: ExtractedMetadata(null, null, 0, null)
        // Directory name beats the first file's tag for a collection's title.
        val collectionMeta = first.copy(title = book.title)
        insert(id, book, SourceKind.MP3_DIR, collectionMeta, durationMs = chapters.sumOf { it.durationMs }, chapters = chapters)
    }

    private suspend fun insert(
        id: String,
        book: ScannedBook,
        kind: SourceKind,
        meta: ExtractedMetadata,
        durationMs: Long,
        chapters: List<ChapterEntity>,
    ) {
        bookDao.upsert(
            listOf(
                BookEntity(
                    id = id,
                    title = meta.title?.takeIf { it.isNotBlank() } ?: book.title,
                    author = meta.author,
                    coverPath = coverStore.save(id, meta.coverBytes),
                    sourceUri = book.rootUri,
                    sourceKind = kind,
                    durationMs = durationMs,
                    addedAtUtc = System.currentTimeMillis(),
                ),
            ),
        )
        chapterDao.upsertAll(chapters)
    }
}
