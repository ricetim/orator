package com.orator.core.designsystem.text

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ShowNotesTest {

    @Test
    fun `strips html and finds mm-ss timestamps`() {
        val rendered = ShowNotes.render("<p>Intro at <b>1:23</b> and outro.</p>")
        assertTrue(rendered.text.contains("Intro at 1:23"))
        val link = rendered.links.single()
        assertEquals(83_000L, link.positionMs)
        assertEquals("1:23", rendered.text.substring(link.startIndex, link.endIndex))
    }

    @Test
    fun `finds hh-mm-ss timestamps`() {
        val rendered = ShowNotes.render("Deep dive at 1:02:03.")
        assertEquals(((1 * 60 + 2) * 60 + 3) * 1000L, rendered.links.single().positionMs)
    }

    @Test
    fun `ignores dates and invalid times`() {
        val rendered = ShowNotes.render("Published 2026-06-10, version 1.2.3, at 99:99.")
        assertTrue(rendered.links.isEmpty())
    }

    @Test
    fun `multiple timestamps keep document order`() {
        val rendered = ShowNotes.render("First 0:30 then 12:34 then 1:00:00")
        assertEquals(listOf(30_000L, 754_000L, 3_600_000L), rendered.links.map { it.positionMs })
    }

    @Test
    fun `plain text without html survives`() {
        val rendered = ShowNotes.render("no markup at 2:00 here")
        assertEquals(120_000L, rendered.links.single().positionMs)
    }
}
