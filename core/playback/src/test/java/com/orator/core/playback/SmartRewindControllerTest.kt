package com.orator.core.playback

import org.junit.Assert.assertEquals
import org.junit.Test

class SmartRewindControllerTest {

    private val c = SmartRewindController()

    @Test
    fun `resume after a long pause on the same item rewinds`() {
        c.onPaused("book/1/0", nowMs = 1_000_000)
        val rewind = c.onResumed("book/1/0", nowMs = 1_000_000 + 10 * 60_000, enabled = true)
        assertEquals(15_000L, rewind)
    }

    @Test
    fun `short pause rewinds nothing`() {
        c.onPaused("book/1/0", nowMs = 0)
        assertEquals(0L, c.onResumed("book/1/0", nowMs = 5_000, enabled = true))
    }

    @Test
    fun `different item rewinds nothing`() {
        c.onPaused("book/1/0", nowMs = 0)
        assertEquals(0L, c.onResumed("book/2/0", nowMs = 10 * 60_000, enabled = true))
    }

    @Test
    fun `disabled per type rewinds nothing`() {
        c.onPaused("book/1/0", nowMs = 0)
        assertEquals(0L, c.onResumed("book/1/0", nowMs = 10 * 60_000, enabled = false))
    }

    @Test
    fun `initial play with no prior pause rewinds nothing`() {
        assertEquals(0L, c.onResumed("book/1/0", nowMs = 10 * 60_000, enabled = true))
    }

    @Test
    fun `a consumed pause does not rewind twice`() {
        c.onPaused("book/1/0", nowMs = 0)
        c.onResumed("book/1/0", nowMs = 10 * 60_000, enabled = true)
        assertEquals(0L, c.onResumed("book/1/0", nowMs = 20 * 60_000, enabled = true))
    }

    @Test
    fun `loading a new queue clears the pending pause`() {
        // The cold-resume path (BookDetailViewModel) already subtracts its own rewind and
        // rebuilds the queue with the SAME mediaId; without this reset the warm path would
        // rewind again on top of it. Same for chapter/bookmark taps after a pause.
        c.onPaused("book/1/0", nowMs = 0)
        c.reset()
        assertEquals(0L, c.onResumed("book/1/0", nowMs = 10 * 60_000, enabled = true))
    }
}
