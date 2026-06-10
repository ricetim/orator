package com.orator.core.playback

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SleepTimerTest {

    @Test
    fun `starts off`() {
        assertEquals(SleepTimerState.Off, SleepTimer().state.value)
    }

    @Test
    fun `arming a duration computes the deadline from the provided clock`() {
        val timer = SleepTimer()
        timer.armDuration(minutes = 30, nowMs = 1_000_000)
        assertEquals(SleepTimerState.Duration(endsAtMs = 1_000_000 + 30 * 60_000L), timer.state.value)
    }

    @Test
    fun `arming boundary mode and cancelling`() {
        val timer = SleepTimer()
        timer.armBoundary()
        assertEquals(SleepTimerState.EndOfBoundary, timer.state.value)
        timer.cancel()
        assertEquals(SleepTimerState.Off, timer.state.value)
    }

    @Test
    fun `next boundary is the first one strictly after the current position`() {
        val boundaries = listOf(0L, 240_000L, 600_000L)
        assertEquals(240_000L, SleepTimer.nextBoundary(boundaries, positionMs = 10_000))
        assertEquals(600_000L, SleepTimer.nextBoundary(boundaries, positionMs = 240_000))
        assertNull(SleepTimer.nextBoundary(boundaries, positionMs = 600_000))
        assertNull(SleepTimer.nextBoundary(emptyList(), positionMs = 0))
    }

    @Test
    fun `unsorted boundaries are tolerated`() {
        assertEquals(
            240_000L,
            SleepTimer.nextBoundary(listOf(600_000L, 0L, 240_000L), positionMs = 10_000),
        )
    }
}
