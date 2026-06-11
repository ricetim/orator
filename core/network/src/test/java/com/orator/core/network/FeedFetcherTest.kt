package com.orator.core.network

import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class FeedFetcherTest {

    private lateinit var server: MockWebServer
    private lateinit var fetcher: FeedFetcher

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
        fetcher = FeedFetcher(OkHttpClient())
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    @Test
    fun `success returns body and validators`() = runBlocking {
        server.enqueue(
            MockResponse()
                .setBody("<rss/>")
                .setHeader("ETag", "\"v1\"")
                .setHeader("Last-Modified", "Wed, 10 Jun 2026 00:00:00 GMT"),
        )

        val result = fetcher.fetch(server.url("/feed").toString())

        result as FetchResult.Success
        assertEquals("<rss/>", result.body)
        assertEquals("\"v1\"", result.etag)
        assertEquals("Wed, 10 Jun 2026 00:00:00 GMT", result.lastModified)
    }

    @Test
    fun `sends conditional headers and maps 304`() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(304))

        val result = fetcher.fetch(
            server.url("/feed").toString(),
            etag = "\"v1\"",
            lastModified = "Wed, 10 Jun 2026 00:00:00 GMT",
        )

        assertEquals(FetchResult.NotModified, result)
        val recorded = server.takeRequest()
        assertEquals("\"v1\"", recorded.getHeader("If-None-Match"))
        assertEquals("Wed, 10 Jun 2026 00:00:00 GMT", recorded.getHeader("If-Modified-Since"))
    }

    @Test
    fun `http error maps to failure`() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(500))

        val result = fetcher.fetch(server.url("/feed").toString())

        assertTrue(result is FetchResult.Failure)
    }

    @Test
    fun `unreachable host maps to failure not exception`() = runBlocking {
        val url = server.url("/feed").toString()
        server.shutdown()

        val result = fetcher.fetch(url)

        assertTrue(result is FetchResult.Failure)
    }

    @Test
    fun `malformed url maps to failure`() = runBlocking {
        val result = fetcher.fetch("not a url")

        assertTrue(result is FetchResult.Failure)
    }
}
