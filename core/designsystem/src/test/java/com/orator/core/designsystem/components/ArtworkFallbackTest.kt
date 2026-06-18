package com.orator.core.designsystem.components

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ArtworkFallbackTest {

    @Test
    fun `initials takes first letters of first two words`() {
        assertEquals("DB", ArtworkFallback.initials("The Daily Brief"))
        assertEquals("D", ArtworkFallback.initials("Dracula"))
        assertEquals("9V", ArtworkFallback.initials("99% Visible"))
        assertEquals("?", ArtworkFallback.initials(""))
    }

    @Test
    fun `leading articles are skipped`() {
        assertEquals("OM", ArtworkFallback.initials("The Orbital Mechanics"))
        assertEquals("PM", ArtworkFallback.initials("A Princess of Mars"))
    }

    @Test
    fun `gradient choice is deterministic for the same title`() {
        val a = ArtworkFallback.gradientFor("Dracula")
        val b = ArtworkFallback.gradientFor("Dracula")
        assertEquals(a, b)
        assertTrue(ArtworkFallback.GRADIENTS.contains(a))
    }
}
