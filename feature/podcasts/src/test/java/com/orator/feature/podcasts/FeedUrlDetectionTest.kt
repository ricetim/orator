package com.orator.feature.podcasts

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FeedUrlDetectionTest {

    @Test
    fun `feed urls are recognized`() {
        assertTrue(looksLikeFeedUrl("https://example.com/feed.xml"))
        assertTrue(looksLikeFeedUrl("http://example.com/rss"))
        assertFalse(looksLikeFeedUrl("daily brief"))
        assertFalse(looksLikeFeedUrl("httpodcast show"))
    }
}
