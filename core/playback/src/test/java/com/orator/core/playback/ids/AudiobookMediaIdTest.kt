package com.orator.core.playback.ids

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class AudiobookMediaIdTest {

    @Test
    fun `mediaId roundtrips`() {
        val id = AudiobookMediaId.encode("abc123", 4)
        val parsed = AudiobookMediaId.parse(id)!!

        assertEquals("abc123", parsed.bookId)
        assertEquals(4, parsed.fileIndex)
    }

    @Test
    fun `parse rejects foreign mediaIds`() {
        assertNull(AudiobookMediaId.parse("podcast/xyz/2"))
        assertNull(AudiobookMediaId.parse(""))
        assertNull(AudiobookMediaId.parse("audiobook/missing-index"))
        assertNull(AudiobookMediaId.parse("audiobook/x/notanumber"))
    }
}
