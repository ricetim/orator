package com.orator.feature.podcasts.data.search

import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class PodcastIndexSearchProviderTest {

    private lateinit var server: MockWebServer

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
    }

    @After
    fun tearDown() = server.shutdown()

    private fun provider(key: String = "k", secret: String = "s") = PodcastIndexSearchProvider(
        client = OkHttpClient(),
        key = key,
        secret = secret,
        baseUrl = server.url("/").toString().trimEnd('/'),
        epochSeconds = { 1_780_000_000L },
    )

    @Test
    fun `sends documented auth headers`() = runBlocking {
        server.enqueue(MockResponse().setBody("""{"status":"true","feeds":[]}"""))

        provider().search("test").getOrThrow()

        val request = server.takeRequest()
        assertEquals("k", request.getHeader("X-Auth-Key"))
        assertEquals("1780000000", request.getHeader("X-Auth-Date"))
        // Literal SHA-1 of the ASCII string "ks1780000000" (printf 'ks1780000000' | sha1sum)
        // — a hardcoded digest catches a wrong algorithm/hex bug that asserting
        // sha1Hex-against-itself would miss.
        assertEquals(
            "34c56d23e1f97c9bf0c5124359b44069755fc2a6",
            request.getHeader("Authorization"),
        )
        assertEquals(SEARCH_USER_AGENT, request.getHeader("User-Agent"))
        assertTrue(request.path!!.contains("/api/1.0/search/byterm"))
    }

    @Test
    fun `maps feeds and drops rows without url`() = runBlocking {
        server.enqueue(
            MockResponse().setBody(
                """{"status":"true","feeds":[
                  {"title":"Show A","author":"Jane","url":"https://x/a.xml","artwork":"https://x/a.jpg"},
                  {"title":"No Url","author":"Bob"},
                  {"title":"Show B","url":"https://x/b.xml","image":"https://x/b.jpg"}
                ]}""",
            ),
        )

        val results = provider().search("x").getOrThrow()

        assertEquals(2, results.size)
        assertEquals("https://x/a.jpg", results[0].artworkUrl)
        assertEquals("https://x/b.jpg", results[1].artworkUrl) // falls back to "image"
    }

    @Test
    fun `blank credentials fail fast without a request`() = runBlocking {
        val result = provider(secret = "").search("x")
        assertTrue(result.isFailure)
        assertEquals(0, server.requestCount)
    }

    @Test
    fun `401 maps to failure`() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(401))
        assertTrue(provider().search("x").isFailure)
    }
}
