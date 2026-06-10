package com.orator.feature.audiobooks.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AudiobookMediaIdTest {

    @Test
    fun `bookId is stable and uri-safe`() {
        val a = BookIds.fromUri("content://com.android.externalstorage.documents/tree/primary%3AAudiobooks/document/primary%3AAudiobooks%2Fbook.m4b")
        val b = BookIds.fromUri("content://com.android.externalstorage.documents/tree/primary%3AAudiobooks/document/primary%3AAudiobooks%2Fbook.m4b")
        val c = BookIds.fromUri("content://other")

        assertEquals(a, b)
        assertTrue(a != c)
        assertEquals(16, a.length)
        assertTrue(a.all { it in "0123456789abcdef" })
    }

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
