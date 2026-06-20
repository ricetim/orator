package com.orator.feature.audiobooks.data

import android.net.Uri
import com.orator.core.database.BookDao
import com.orator.core.database.BookEntity
import com.orator.core.database.ChapterDao
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
        // One file for SINGLE_FILE; the natural-sorted list for MULTI_FILE.
        val files = when (book) {
            is ScannedBook.SingleFile -> listOf(ScannedFile(name = book.title, uri = book.rootUri))
            is ScannedBook.MultiFile -> book.files
        }

        var firstMeta: ExtractedMetadata? = null
        val fileChapters = files.map { f ->
            val uri = Uri.parse(f.uri)
            val meta = extractor.extract(uri)
            if (firstMeta == null) firstMeta = meta
            ChapterAssembler.FileChapters(
                fileUri = f.uri,
                durationMs = meta.durationMs,
                marks = chapterSource.chaptersOf(uri),
                fallbackTitle = f.name.substringBeforeLast('.'),
            )
        }
        val chapters = ChapterAssembler.assemble(id, fileChapters)
        val meta = firstMeta ?: ExtractedMetadata(null, null, 0, null)

        val kind = when (book) {
            is ScannedBook.SingleFile -> SourceKind.SINGLE_FILE
            is ScannedBook.MultiFile -> SourceKind.MULTI_FILE
        }
        // A lone file prefers its embedded title; a multi-file book is named by its directory.
        val title = when (book) {
            is ScannedBook.SingleFile -> meta.title?.takeIf { it.isNotBlank() } ?: book.title
            is ScannedBook.MultiFile -> book.title
        }

        bookDao.upsert(
            listOf(
                BookEntity(
                    id = id,
                    title = title,
                    author = meta.author,
                    coverPath = coverStore.save(id, meta.coverBytes),
                    sourceUri = book.rootUri,
                    sourceKind = kind,
                    durationMs = chapters.sumOf { it.durationMs },
                    addedAtUtc = System.currentTimeMillis(),
                ),
            ),
        )
        chapterDao.upsertAll(chapters)
    }
}
