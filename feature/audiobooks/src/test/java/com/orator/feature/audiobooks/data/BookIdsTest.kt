package com.orator.feature.audiobooks.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class BookIdsTest {

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
}
