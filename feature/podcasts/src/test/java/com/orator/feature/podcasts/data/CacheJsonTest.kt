package com.orator.feature.podcasts.data

import com.orator.core.database.EpisodeEntity
import com.orator.core.database.PodcastEntity
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class CacheJsonTest {

    @Test
    fun `show json round-trips key fields`() {
        val podcast = PodcastEntity(
            id = "p1", feedUrl = "https://x/feed.xml", title = "Show",
            author = "Jane", description = "About", artworkUrl = "https://x/c.jpg",
            subscribedAtUtc = 5,
        )
        val parsed = JSONObject(CacheJson.showJson(podcast))
        assertEquals("Show", parsed.getString("title"))
        assertEquals("https://x/feed.xml", parsed.getString("feedUrl"))
        assertEquals("Jane", parsed.getString("author"))
    }

    @Test
    fun `episode json includes guid source id and enclosure`() {
        val episode = EpisodeEntity(
            id = "e1", podcastId = "p1", title = "Ep", pubDateUtc = 7,
            durationMs = 1000, enclosureUrl = "https://x/e.mp3",
        )
        val parsed = JSONObject(CacheJson.episodeJson(episode))
        assertEquals("e1", parsed.getString("id"))
        assertEquals("https://x/e.mp3", parsed.getString("enclosureUrl"))
        assertEquals(1000, parsed.getLong("durationMs"))
    }
}
