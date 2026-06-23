package com.orator.feature.audiobookshelf.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class AbsCredentialStoreTest {
    private class FakeSecure : SecureStringStore {
        val map = mutableMapOf<String, String>()
        override fun get(key: String) = map[key]
        override fun put(key: String, value: String) { map[key] = value }
        override fun clear() = map.clear()
    }

    @Test fun `save then current returns the session and host matches base`() {
        val store = AbsCredentialStore(FakeSecure())
        val cfg = AbsServerConfig("https://abs.example.com", "https://abs.example.com", "u", "tok")
        store.save(cfg)
        assertEquals(cfg, store.current()!!.config)
        assertEquals("abs.example.com", store.current()!!.baseUrl.host)
    }

    @Test fun `survives a fresh instance backed by the same secure store`() {
        val secure = FakeSecure()
        AbsCredentialStore(secure).save(
            AbsServerConfig("https://abs.example.com", "https://abs.example.com", "u", "tok"),
        )
        assertEquals("tok", AbsCredentialStore(secure).current()!!.config.token)
    }

    @Test fun `clear wipes it`() {
        val store = AbsCredentialStore(FakeSecure())
        store.save(AbsServerConfig("https://x", "https://x", "u", "t"))
        store.clear()
        assertNull(store.current())
    }
}
