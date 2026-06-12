package com.orator.core.designsystem.text

import org.junit.Assert.assertEquals
import org.junit.Test

class TimeFormatsTest {

    @Test
    fun `clock formats with and without hours`() {
        assertEquals("0:00", TimeFormats.clock(0))
        assertEquals("41:08", TimeFormats.clock(41 * 60_000L + 8_000))
        assertEquals("1:02:22", TimeFormats.clock(62 * 60_000L + 22_000))
    }

    @Test
    fun `remaining is minus-prefixed clock of duration minus position`() {
        assertEquals(
            "−41:08",
            TimeFormats.remaining(
                positionMs = 21 * 60_000L + 14_000,
                durationMs = 62 * 60_000L + 22_000,
            ),
        )
        assertEquals("−0:00", TimeFormats.remaining(positionMs = 10_000, durationMs = 5_000))
    }

    @Test
    fun `timeLeft renders hours and minutes coarsely`() {
        assertEquals("9h 14m left", TimeFormats.timeLeft(9 * 3_600_000L + 14 * 60_000L))
        assertEquals("41m left", TimeFormats.timeLeft(41 * 60_000L))
        assertEquals("<1m left", TimeFormats.timeLeft(20_000))
    }

    @Test
    fun `relativeDay buckets correctly`() {
        val now = 1_770_000_000_000L
        assertEquals("today", TimeFormats.relativeDay(now - 3_600_000, now))
        assertEquals("yesterday", TimeFormats.relativeDay(now - 26 * 3_600_000L, now))
        assertEquals("3d ago", TimeFormats.relativeDay(now - 3 * 86_400_000L - 3_600_000, now))
        assertEquals("5w ago", TimeFormats.relativeDay(now - 37 * 86_400_000L, now))
    }

    @Test
    fun `miniSubLine composes remaining, speed and trim flag`() {
        assertEquals(
            "−41:08 · 1.2×",
            TimeFormats.miniSubLine(21 * 60_000L + 14_000, 62 * 60_000L + 22_000, 1.2f, false),
        )
        assertEquals(
            "−41:08 · 1.2× · trim",
            TimeFormats.miniSubLine(21 * 60_000L + 14_000, 62 * 60_000L + 22_000, 1.2f, true),
        )
        assertEquals("1×", TimeFormats.miniSubLine(0, 0, 1.0f, false))
    }

    @Test
    fun `speedLabel drops trailing zeros`() {
        assertEquals("1×", TimeFormats.speedLabel(1.0f))
        assertEquals("1.2×", TimeFormats.speedLabel(1.2f))
        assertEquals("1.25×", TimeFormats.speedLabel(1.25f))
    }
}
