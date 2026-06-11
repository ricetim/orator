package com.orator.feature.podcasts.data

import org.junit.Assert.assertEquals
import org.junit.Test

class CacheNamesTest {

    @Test
    fun `sanitizes illegal filename characters`() {
        // Apostrophes are legal in SAF filenames and are kept.
        assertEquals("What's Up_ Doc_", CacheNames.sanitize("What's Up? Doc:"))
        assertEquals("a_b_c", CacheNames.sanitize("a/b\\c"))
    }

    @Test
    fun `trims trailing dots and spaces and caps length`() {
        assertEquals("ends", CacheNames.sanitize("ends. . ."))
        assertEquals(80, CacheNames.sanitize("x".repeat(200)).length)
    }

    @Test
    fun `blank becomes untitled`() {
        assertEquals("untitled", CacheNames.sanitize("  "))
    }

    @Test
    fun `episode dir name is date-prefixed`() {
        // 1_780_992_000_000 ms = 2026-06-09T08:00:00Z (verified: date -u -d @1780992000)
        assertEquals(
            "2026-06-09 - My Episode",
            CacheNames.episodeDirName(1_780_992_000_000L, "My Episode"),
        )
    }

    @Test
    fun `unknown date prefixes with 0000`() {
        assertEquals("0000-00-00 - Mystery", CacheNames.episodeDirName(0L, "Mystery"))
    }

    @Test
    fun `collision suffix appends short id`() {
        assertEquals("Show [abcd]", CacheNames.withIdSuffix("Show", "abcdef0123456789"))
    }
}
