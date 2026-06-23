package com.orator.feature.audiobookshelf.data

import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AbsAuthRepositoryTest {
    private val store = AbsCredentialStore(object : SecureStringStore {
        val m = mutableMapOf<String, String>()
        override fun get(key: String) = m[key]
        override fun put(key: String, value: String) { m[key] = value }
        override fun clear() = m.clear()
    })

    @Test fun `successful login saves token, syncs, and reports Connected`() = runBlocking {
        var synced = false
        val repo = AbsAuthRepository(
            loginFn = { _, _, _ -> AbsUser(id = "u", token = "tok") },
            store = store,
            onConnected = { synced = true },
        )
        repo.login("https://abs.example.com/", "reader", "pw")
        assertEquals("tok", store.current()!!.config.token)
        assertTrue(synced)
        assertTrue(repo.state.first() is AbsConnectionState.Connected)
    }

    @Test fun `failed login reports Error and stores nothing`() = runBlocking {
        val repo = AbsAuthRepository(
            loginFn = { _, _, _ -> throw RuntimeException("nope") },
            store = store,
            onConnected = {},
        )
        repo.login("https://abs.example.com/", "reader", "pw")
        assertTrue(repo.state.first() is AbsConnectionState.Error)
        assertEquals(null, store.current())
    }
}
