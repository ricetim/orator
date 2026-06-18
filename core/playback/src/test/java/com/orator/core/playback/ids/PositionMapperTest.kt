package com.orator.core.playback.ids

import org.junit.Assert.assertEquals
import org.junit.Test

class PositionMapperTest {

    private val durations = listOf(10_000L, 20_000L, 30_000L) // total 60s

    @Test
    fun `global position maps into the right file`() {
        assertEquals(PositionMapper.FilePosition(0, 5_000), PositionMapper.toFilePosition(durations, 5_000))
        assertEquals(PositionMapper.FilePosition(1, 0), PositionMapper.toFilePosition(durations, 10_000))
        assertEquals(PositionMapper.FilePosition(2, 15_000), PositionMapper.toFilePosition(durations, 45_000))
    }

    @Test
    fun `file position maps back to global`() {
        assertEquals(45_000L, PositionMapper.toGlobal(durations, 2, 15_000))
        assertEquals(0L, PositionMapper.toGlobal(durations, 0, 0))
    }

    @Test
    fun `roundtrip is identity`() {
        val global = 33_333L
        val fp = PositionMapper.toFilePosition(durations, global)
        assertEquals(global, PositionMapper.toGlobal(durations, fp.fileIndex, fp.offsetMs))
    }

    @Test
    fun `out-of-range global clamps to the end of the last file`() {
        assertEquals(PositionMapper.FilePosition(2, 30_000), PositionMapper.toFilePosition(durations, 999_999))
    }

    @Test
    fun `negative global clamps to start`() {
        assertEquals(PositionMapper.FilePosition(0, 0), PositionMapper.toFilePosition(durations, -5))
    }

    @Test
    fun `empty duration list yields origin`() {
        assertEquals(PositionMapper.FilePosition(0, 0), PositionMapper.toFilePosition(emptyList(), 1_000))
    }
}
