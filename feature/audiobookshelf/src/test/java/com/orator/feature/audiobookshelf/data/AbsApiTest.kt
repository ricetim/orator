package com.orator.feature.audiobookshelf.data

import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.assertEquals
import org.junit.Test

class AbsApiTest {
    private val client = OkHttpClient()

    @Test fun `login parses user token`() = runBlocking {
        val server = MockWebServer().apply {
            enqueue(MockResponse().setBody("""{"user":{"id":"u1","username":"reader","token":"abc123"}}"""))
            start()
        }
        val api = AbsApi(client, AbsJson.instance)
        val user = api.login(server.url("/").toString(), "reader", "pw")
        assertEquals("abc123", user.token)
        assertEquals("/login", server.takeRequest().path)
        server.shutdown()
    }

    @Test fun `getLibraryItems parses minified items and ignores unknown fields`() = runBlocking {
        val server = MockWebServer().apply {
            enqueue(
                MockResponse().setBody(
                    """{"results":[{"id":"li1","media":{"metadata":{"title":"Dune","authorName":"Herbert"},
                       "numAudioFiles":3,"duration":42.5},"unknownField":true}]}""".trimIndent(),
                ),
            )
            start()
        }
        val api = AbsApi(client, AbsJson.instance)
        val page = api.getLibraryItems(server.url("/").toString(), "lib1", "tok")
        assertEquals(1, page.results.size)
        assertEquals("Dune", page.results[0].media.metadata.title)
        assertEquals("Herbert", page.results[0].media.metadata.authorName)
        assertEquals(3, page.results[0].media.numAudioFiles)
        assertEquals("/api/libraries/lib1/items?minified=1&limit=0", server.takeRequest().path)
        server.shutdown()
    }
}
