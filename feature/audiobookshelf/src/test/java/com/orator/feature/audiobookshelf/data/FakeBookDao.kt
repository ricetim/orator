package com.orator.feature.audiobookshelf.data

import com.orator.core.database.BookDao
import com.orator.core.database.BookEntity
import com.orator.core.model.BookOrigin
import kotlinx.coroutines.flow.Flow

/** In-memory BookDao for tests; observe/Flow members are unused here. */
class FakeBookDao : BookDao {
    val books = linkedMapOf<String, BookEntity>()

    override suspend fun upsert(books: List<BookEntity>) {
        books.forEach { this.books[it.id] = it }
    }

    override fun observeAll(): Flow<List<BookEntity>> = throw NotImplementedError()
    override fun observeCount(): Flow<Int> = throw NotImplementedError()
    override fun observeById(id: String): Flow<BookEntity?> = throw NotImplementedError()
    override suspend fun getById(id: String): BookEntity? = books[id]
    override suspend fun getAllIds(): List<String> = books.keys.toList()
    override suspend fun getByOrigin(origin: BookOrigin): List<BookEntity> =
        books.values.filter { it.origin == origin }
    override suspend fun getIdsByOrigin(origin: BookOrigin): List<String> =
        books.values.filter { it.origin == origin }.map { it.id }

    override suspend fun updateProgress(id: String, positionMs: Long, lastPlayedAtMs: Long) {
        books[id]?.let { books[id] = it.copy(positionMs = positionMs, lastPlayedAtMs = lastPlayedAtMs) }
    }

    override suspend fun updateSpeedOverride(id: String, speed: Float?) {
        books[id]?.let { books[id] = it.copy(speedOverride = speed) }
    }

    override suspend fun deleteByIds(ids: List<String>) {
        ids.forEach { books.remove(it) }
    }
}
