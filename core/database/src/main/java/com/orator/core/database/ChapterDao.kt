package com.orator.core.database

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

@Dao
interface ChapterDao {
    @Upsert
    suspend fun upsertAll(chapters: List<ChapterEntity>)

    @Query("SELECT * FROM chapters WHERE bookId = :bookId ORDER BY chapterIndex")
    suspend fun getForBook(bookId: String): List<ChapterEntity>

    @Query("SELECT * FROM chapters WHERE bookId = :bookId ORDER BY chapterIndex")
    fun observeForBook(bookId: String): Flow<List<ChapterEntity>>

    @Query("DELETE FROM chapters WHERE bookId = :bookId")
    suspend fun deleteForBook(bookId: String)

    /** Default DAO method (Room supports these on interfaces): swap a book's chapters wholesale. */
    suspend fun replaceForBook(bookId: String, chapters: List<ChapterEntity>) {
        deleteForBook(bookId)
        upsertAll(chapters)
    }
}
