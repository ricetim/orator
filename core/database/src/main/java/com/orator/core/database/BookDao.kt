package com.orator.core.database

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

@Dao
interface BookDao {
    @Upsert
    suspend fun upsert(books: List<BookEntity>)

    @Query("SELECT * FROM books ORDER BY title")
    fun observeAll(): Flow<List<BookEntity>>

    @Query("SELECT * FROM books WHERE id = :id")
    fun observeById(id: String): Flow<BookEntity?>

    @Query("SELECT * FROM books WHERE id = :id")
    suspend fun getById(id: String): BookEntity?

    @Query("SELECT id FROM books")
    suspend fun getAllIds(): List<String>

    @Query("UPDATE books SET positionMs = :positionMs, lastPlayedAtMs = :lastPlayedAtMs WHERE id = :id")
    suspend fun updateProgress(id: String, positionMs: Long, lastPlayedAtMs: Long)

    @Query("UPDATE books SET speedOverride = :speed WHERE id = :id")
    suspend fun updateSpeedOverride(id: String, speed: Float?)

    @Query("DELETE FROM books WHERE id IN (:ids)")
    suspend fun deleteByIds(ids: List<String>)
}
