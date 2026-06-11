package com.orator.feature.podcasts.data

import org.junit.Assert.assertEquals
import org.junit.Test

class AudioExtTest {
    @Test
    fun `maps mime types and falls back to url extension then mp3`() {
        assertEquals("mp3", EpisodeDownloader.audioExt("audio/mpeg", "https://x/e?id=1"))
        assertEquals("m4a", EpisodeDownloader.audioExt("audio/mp4", "https://x/e"))
        assertEquals("m4a", EpisodeDownloader.audioExt("audio/x-m4a", "https://x/e"))
        assertEquals("ogg", EpisodeDownloader.audioExt("audio/ogg", "https://x/e"))
        assertEquals("m4a", EpisodeDownloader.audioExt(null, "https://x/ep.m4a?tok=2"))
        assertEquals("mp3", EpisodeDownloader.audioExt(null, "https://x/ep"))
    }
}
