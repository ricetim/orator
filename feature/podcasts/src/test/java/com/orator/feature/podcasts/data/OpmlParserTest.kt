package com.orator.feature.podcasts.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

// Robolectric for the same reason as RssParserTest: XmlPullParserFactory is Android API.
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class OpmlParserTest {

    private fun load(name: String): String =
        checkNotNull(javaClass.classLoader).getResourceAsStream(name)!!
            .readBytes().decodeToString()

    @Test
    fun `extracts all feeds regardless of nesting`() {
        val feeds = OpmlParser.parse(load("feeds.opml"))
        assertEquals(3, feeds.size)
        assertEquals("Show A", feeds[0].title)
        assertEquals("https://example.com/a.xml", feeds[0].xmlUrl)
        assertEquals("https://example.com/c.xml", feeds[2].xmlUrl)
    }

    @Test
    fun `garbage returns empty list`() {
        assertTrue(OpmlParser.parse("not xml").isEmpty())
    }
}
