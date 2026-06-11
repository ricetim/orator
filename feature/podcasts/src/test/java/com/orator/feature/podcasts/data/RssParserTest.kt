package com.orator.feature.podcasts.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

// Robolectric is REQUIRED: XmlPullParserFactory compiles against the mockable android.jar but
// throws "not mocked" at runtime in plain local tests; only the Robolectric sandbox provides
// the real (kxml2) implementation.
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class RssParserTest {

    private fun load(name: String): String =
        checkNotNull(javaClass.classLoader).getResourceAsStream(name)!!
            .readBytes().decodeToString()

    @Test
    fun `parses channel metadata and items`() {
        val feed = RssParser.parse(load("full.xml"))!!

        assertEquals("Test Show & Friends", feed.title)
        assertEquals("Jane Host", feed.author)
        assertEquals("A show about tests.", feed.description)
        assertEquals("https://example.com/cover.jpg", feed.artworkUrl)
        assertEquals(2, feed.items.size)
    }

    @Test
    fun `parses durations in both formats`() {
        val items = RssParser.parse(load("full.xml"))!!.items
        assertEquals(((1 * 60 + 2) * 60 + 3) * 1000L, items[0].durationMs) // 01:02:03
        assertEquals(1830_000L, items[1].durationMs)                        // bare seconds
    }

    @Test
    fun `parses decimal durations some feeds emit`() {
        val xml = """<?xml version="1.0"?><rss version="2.0"><channel><title>S</title>
            <item><title>E</title><itunes:duration>5400.0</itunes:duration>
            <enclosure url="https://x/e.mp3" type="audio/mpeg"/></item></channel></rss>"""
        assertEquals(5_400_000L, RssParser.parse(xml)!!.items.single().durationMs)
    }

    @Test
    fun `prefers content-encoded over description for show notes`() {
        val items = RssParser.parse(load("full.xml"))!!.items
        assertTrue(items[0].showNotesHtml!!.contains("Rich notes"))
        assertTrue(items[1].showNotesHtml!!.contains("<b>Show notes</b>"))
    }

    @Test
    fun `parses rfc1123 pubDates`() {
        val items = RssParser.parse(load("full.xml"))!!.items
        assertTrue(items[0].pubDateUtc > items[1].pubDateUtc)
        assertTrue(items[0].pubDateUtc > 0)
    }

    @Test
    fun `minimal feed parses with defaults`() {
        val feed = RssParser.parse(load("minimal.xml"))!!
        val item = feed.items.single()
        assertNull(item.guid)
        assertEquals(0L, item.durationMs)
        assertEquals(0L, item.pubDateUtc)
        assertEquals("https://example.com/only.mp3", item.enclosureUrl)
    }

    @Test
    fun `skips items missing title or enclosure without aborting feed`() {
        val feed = RssParser.parse(load("broken-items.xml"))!!
        assertEquals(1, feed.items.size)
        assertEquals("Good One", feed.items[0].title)
    }

    @Test
    fun `garbage input returns null instead of throwing`() {
        assertNull(RssParser.parse("this is not xml at all <<<"))
        assertNull(RssParser.parse("<html><body>404</body></html>"))
    }
}
