package com.orator.core.playback

import org.junit.Assert.assertEquals
import org.junit.Test

class SmartRewindTest {

    @Test
    fun `short pause rewinds nothing`() {
        assertEquals(0L, SmartRewind.rewindMs(pausedForMs = 0))
        assertEquals(0L, SmartRewind.rewindMs(pausedForMs = 29_999))
    }

    @Test
    fun `medium pause rewinds five seconds`() {
        assertEquals(5_000L, SmartRewind.rewindMs(pausedForMs = 30_000))
        assertEquals(5_000L, SmartRewind.rewindMs(pausedForMs = 5 * 60_000L - 1))
    }

    @Test
    fun `long pause rewinds fifteen seconds`() {
        assertEquals(15_000L, SmartRewind.rewindMs(pausedForMs = 5 * 60_000L))
        assertEquals(15_000L, SmartRewind.rewindMs(pausedForMs = 60 * 60_000L - 1))
    }

    @Test
    fun `very long pause rewinds thirty seconds`() {
        assertEquals(30_000L, SmartRewind.rewindMs(pausedForMs = 60 * 60_000L))
        assertEquals(30_000L, SmartRewind.rewindMs(pausedForMs = Long.MAX_VALUE))
    }

    @Test
    fun `negative pause duration rewinds nothing`() {
        // Clock skew (e.g. device time changed) must not produce a forward seek.
        assertEquals(0L, SmartRewind.rewindMs(pausedForMs = -5_000))
    }
}
