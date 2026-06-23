package com.orator.feature.audiobookshelf.data

import com.orator.core.database.BookEntity
import com.orator.core.model.BookOrigin
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test

class AbsRepositoryTest {
    private val store = AbsCredentialStore(object : SecureStringStore {
        val m = mutableMapOf<String, String>()
        override fun get(key: String) = m[key]
        override fun put(key: String, value: String) { m[key] = value }
        override fun clear() = m.clear()
    }).apply { save(AbsServerConfig("https://abs.example.com", "https://abs.example.com", "u", "tok")) }

    private val fakeDao = FakeBookDao()

    private val source = object : AbsCatalogSource {
        override suspend fun libraries(baseUrl: String, token: String) =
            listOf(AbsLibrary("lib1", "Books", "book"))
        override suspend fun items(baseUrl: String, libraryId: String, token: String) =
            listOf(AbsLibraryItem("li1", AbsMedia(metadata = AbsMetadata(title = "Dune"))))
    }

    @Test fun `sync mirrors items into the books table as ABS rows`() = runBlocking {
        AbsRepository(source, store, fakeDao).sync()
        assertEquals(listOf("abs:li1"), fakeDao.getIdsByOrigin(BookOrigin.ABS))
        assertEquals("Dune", fakeDao.getById("abs:li1")!!.title)
    }

    @Test fun `logout clears credentials and removes ABS books`() = runBlocking {
        val repo = AbsRepository(source, store, fakeDao)
        repo.sync()
        repo.logout()
        assertEquals(emptyList<BookEntity>(), fakeDao.getByOrigin(BookOrigin.ABS))
        assertEquals(null, store.current())
    }
}
