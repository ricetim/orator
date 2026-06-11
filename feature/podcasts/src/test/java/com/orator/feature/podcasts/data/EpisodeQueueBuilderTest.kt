package com.orator.feature.podcasts.data

import com.orator.core.database.EpisodeEntity
import com.orator.core.database.PodcastEntity
import com.orator.core.model.MediaType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class EpisodeQueueBuilderTest {

    private fun podcast(intro: Long = 0, outro: Long = 0, speed: Float? = null) = PodcastEntity(
        id = "p1", feedUrl = "https://x/f.xml", title = "Show", author = null,
        description = null, artworkUrl = null, subscribedAtUtc = 0,
        clipIntroMs = intro, clipOutroMs = outro, speedOverride = speed,
    )

    private fun episode(durationMs: Long = 0, audioPath: String? = null) = EpisodeEntity(
        id = "e1", podcastId = "p1", title = "Ep", pubDateUtc = 0,
        durationMs = durationMs, enclosureUrl = "https://x/e.mp3", audioPath = audioPath,
    )

    @Test
    fun `streams from enclosure when not downloaded`() {
        val request = EpisodeQueueBuilder.build(podcast(), episode(), 0)
        assertEquals("https://x/e.mp3", request.items.single().uri)
        assertEquals(MediaType.PODCAST, request.mediaType)
    }

    @Test
    fun `plays local file when downloaded`() {
        val request =
            EpisodeQueueBuilder.build(podcast(), episode(audioPath = "content://dl/a.mp3"), 0)
        assertEquals("content://dl/a.mp3", request.items.single().uri)
    }

    @Test
    fun `applies intro and outro clips`() {
        val request = EpisodeQueueBuilder.build(
            podcast(intro = 30_000, outro = 60_000), episode(durationMs = 600_000), 0,
        )
        val item = request.items.single()
        assertEquals(30_000L, item.clipStartMs)
        assertEquals(540_000L, item.clipEndMs)
    }

    @Test
    fun `no outro clip when duration unknown`() {
        val request = EpisodeQueueBuilder.build(
            podcast(intro = 30_000, outro = 60_000), episode(durationMs = 0), 0,
        )
        val item = request.items.single()
        assertEquals(30_000L, item.clipStartMs)
        assertNull(item.clipEndMs)
    }

    @Test
    fun `degenerate outro larger than duration leaves a playable sliver`() {
        val request = EpisodeQueueBuilder.build(
            podcast(intro = 30_000, outro = 600_000), episode(durationMs = 100_000), 0,
        )
        val item = request.items.single()
        // clipEnd is clamped above clipStart so Media3 never gets an empty/inverted window
        assertEquals(31_000L, item.clipEndMs)
    }

    @Test
    fun `carries start position speed override and mediaId`() {
        val request = EpisodeQueueBuilder.build(podcast(speed = 1.5f), episode(), 42_000)
        assertEquals(42_000L, request.startPositionMs)
        assertEquals(1.5f, request.speedOverride)
        assertEquals("podcast/e1", request.items.single().mediaId)
    }
}
