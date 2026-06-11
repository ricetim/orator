package com.orator.feature.podcasts.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PodcastIdsTest {

    @Test
    fun `ids are deterministic and url-safe`() {
        val a = PodcastIds.podcastId("https://example.com/feed.xml")
        assertEquals(a, PodcastIds.podcastId("https://example.com/feed.xml"))
        assertEquals(16, a.length)
        assertTrue(a.all { it.isDigit() || it in 'a'..'f' })
    }

    @Test
    fun `same guid in different podcasts yields different episode ids`() {
        val e1 = PodcastIds.episodeId("pod-a", "ep-1")
        val e2 = PodcastIds.episodeId("pod-b", "ep-1")
        assertNotEquals(e1, e2)
    }

    @Test
    fun `media id round-trips`() {
        val id = PodcastIds.episodeId("pod-a", "ep-1")
        assertEquals(id, PodcastMediaId.parse(PodcastMediaId.encode(id)))
    }

    @Test
    fun `media id rejects foreign ids`() {
        assertNull(PodcastMediaId.parse("audiobook/book1/0"))
        assertNull(PodcastMediaId.parse("garbage"))
    }
}
