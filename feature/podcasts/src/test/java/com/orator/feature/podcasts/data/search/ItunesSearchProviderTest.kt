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
class ItunesSearchProviderTest {

    private lateinit var server: MockWebServer
    private lateinit var provider: ItunesSearchProvider

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
        provider = ItunesSearchProvider(OkHttpClient(), baseUrl = server.url("/").toString().trimEnd('/'))
    }

    @After
    fun tearDown() = server.shutdown()

    @Test
    fun `maps results and drops rows without a feed url`() = runBlocking {
        server.enqueue(
            MockResponse().setBody(
                """{"resultCount":3,"results":[
                  {"collectionName":"Show A","artistName":"Jane","feedUrl":"https://x/a.xml","artworkUrl600":"https://x/a.jpg"},
                  {"collectionName":"No Feed","artistName":"Bob"},
                  {"collectionName":"Show B","feedUrl":"https://x/b.xml"}
                ]}""",
            ),
        )

        val results = provider.search("test").getOrThrow()

        assertEquals(2, results.size)
        assertEquals("Show A", results[0].title)
        assertEquals("Jane", results[0].author)
        assertEquals("https://x/a.xml", results[0].feedUrl)
        assertEquals("https://x/a.jpg", results[0].artworkUrl)
        assertEquals("https://x/b.xml", results[1].feedUrl)
        val request = server.takeRequest()
        assertTrue(request.path!!.contains("media=podcast"))
        assertTrue(request.path!!.contains("term=test"))
    }

    @Test
    fun `http error maps to failure`() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(500))
        assertTrue(provider.search("x").isFailure)
    }
}
