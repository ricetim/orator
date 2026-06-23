package com.orator.feature.audiobookshelf.data

import com.orator.core.database.ChapterDao
import com.orator.core.database.ChapterEntity
import kotlinx.coroutines.flow.Flow

/** In-memory ChapterDao for tests; getForBook returns rows sorted by chapterIndex. */
class FakeChapterDao : ChapterDao {
    private val byBook = linkedMapOf<String, MutableList<ChapterEntity>>()

    override suspend fun upsertAll(chapters: List<ChapterEntity>) {
        chapters.forEach { byBook.getOrPut(it.bookId) { mutableListOf() }.add(it) }
    }

    override suspend fun getForBook(bookId: String): List<ChapterEntity> =
        (byBook[bookId] ?: emptyList()).sortedBy { it.chapterIndex }

    override fun observeForBook(bookId: String): Flow<List<ChapterEntity>> = throw NotImplementedError()

    override suspend fun deleteForBook(bookId: String) { byBook.remove(bookId) }

    override suspend fun replaceForBook(bookId: String, chapters: List<ChapterEntity>) {
        deleteForBook(bookId)
        upsertAll(chapters)
    }
}
