package com.orator.feature.podcasts.data

import org.junit.Assert.assertEquals
import org.junit.Test

class RefreshPresetsTest {

    @Test fun `labels cover every preset`() {
        assertEquals("Off", RefreshPresets.label(0))
        assertEquals("15m", RefreshPresets.label(15))
        assertEquals("1h", RefreshPresets.label(60))
        assertEquals("6h", RefreshPresets.label(360))
        assertEquals("Daily", RefreshPresets.label(1440))
    }

    @Test fun `next cycles forward and wraps daily back to off`() {
        assertEquals(15, RefreshPresets.next(0))
        assertEquals(360, RefreshPresets.next(180))
        assertEquals(0, RefreshPresets.next(1440))
    }

    @Test fun `next of an unknown value falls to the first preset`() {
        assertEquals(0, RefreshPresets.next(999))
    }
}
