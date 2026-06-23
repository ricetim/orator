package com.orator.feature.audiobookshelf.data

import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.assertEquals
import org.junit.Test

class AbsAuthInterceptorTest {
    private fun storeFor(baseUrl: String): AbsCredentialStore {
        val s = AbsCredentialStore(object : SecureStringStore {
            val m = mutableMapOf<String, String>()
            override fun get(key: String) = m[key]
            override fun put(key: String, value: String) { m[key] = value }
            override fun clear() = m.clear()
        })
        s.save(AbsServerConfig(baseUrl.trimEnd('/'), baseUrl, "u", "secret-token"))
        return s
    }

    @Test fun `adds bearer to the configured host`() {
        val server = MockWebServer().apply { enqueue(MockResponse()); start() }
        val base = server.url("/").toString()
        val client = OkHttpClient.Builder().addInterceptor(AbsAuthInterceptor(storeFor(base))).build()
        client.newCall(Request.Builder().url(server.url("/api/libraries")).build()).execute().close()
        assertEquals("Bearer secret-token", server.takeRequest().getHeader("Authorization"))
        server.shutdown()
    }

    @Test fun `does not add bearer to a different host`() {
        val absServer = MockWebServer().apply { start() }
        val other = MockWebServer().apply { enqueue(MockResponse()); start() }
        val client = OkHttpClient.Builder()
            .addInterceptor(AbsAuthInterceptor(storeFor(absServer.url("/").toString()))).build()
        client.newCall(Request.Builder().url(other.url("/feed.xml")).build()).execute().close()
        assertEquals(null, other.takeRequest().getHeader("Authorization"))
        absServer.shutdown(); other.shutdown()
    }
}
