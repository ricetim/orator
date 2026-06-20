package com.orator.feature.playlists.data

import com.orator.core.model.MediaRef
import com.orator.core.model.MediaType
import com.orator.core.playback.ids.AudiobookMediaId
import com.orator.core.playback.ids.PodcastMediaId
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MediaRefMatchTest {

    @Test fun `podcast ref matches its encoded media id`() {
        val ref = MediaRef(MediaType.PODCAST, "ep-1")
        assertTrue(MediaRefMatch.matches(ref, PodcastMediaId.encode("ep-1")))
        assertFalse(MediaRefMatch.matches(ref, PodcastMediaId.encode("ep-2")))
    }

    @Test fun `audiobook ref matches any file index of the same book`() {
        val ref = MediaRef(MediaType.AUDIOBOOK, "book-1")
        assertTrue(MediaRefMatch.matches(ref, AudiobookMediaId.encode("book-1", 0)))
        assertTrue(MediaRefMatch.matches(ref, AudiobookMediaId.encode("book-1", 7))) // mid-book
        assertFalse(MediaRefMatch.matches(ref, AudiobookMediaId.encode("book-2", 0)))
    }

    @Test fun `type mismatch never matches`() {
        assertFalse(MediaRefMatch.matches(MediaRef(MediaType.PODCAST, "x"), AudiobookMediaId.encode("x", 0)))
        assertFalse(MediaRefMatch.matches(MediaRef(MediaType.AUDIOBOOK, "x"), PodcastMediaId.encode("x")))
    }

    @Test fun `null or blank media id is not a match`() {
        assertFalse(MediaRefMatch.matches(MediaRef(MediaType.PODCAST, "x"), null))
        assertFalse(MediaRefMatch.matches(MediaRef(MediaType.PODCAST, "x"), ""))
    }
}
