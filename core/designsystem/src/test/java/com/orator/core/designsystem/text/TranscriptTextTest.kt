package com.orator.core.designsystem.text

import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class TranscriptTextTest {

    @Test
    fun `vtt keeps cue text and strips timing header and voice tags`() {
        val vtt = """
            WEBVTT

            00:00:01.000 --> 00:00:04.000
            <v Jane>Hello there.</v>

            2
            00:00:04.000 --> 00:00:06.000
            General Kenobi.
        """.trimIndent()
        assertEquals("Hello there.\nGeneral Kenobi.", TranscriptText.render(vtt, "text/vtt"))
    }

    @Test
    fun `srt drops indices and timing lines`() {
        val srt = """
            1
            00:00:01,000 --> 00:00:04,000
            First line.

            2
            00:00:04,000 --> 00:00:06,000
            Second line.
        """.trimIndent()
        assertEquals("First line.\nSecond line.", TranscriptText.render(srt, "application/srt"))
    }

    @Test
    fun `json concatenates segment bodies`() {
        val json = """{"version":"1.0","segments":[
            {"speaker":"Jane","startTime":0,"endTime":4,"body":"Hello there."},
            {"speaker":"Ben","startTime":4,"endTime":6,"body":"General Kenobi."}]}"""
        assertEquals("Hello there. General Kenobi.", TranscriptText.render(json, "application/json"))
    }

    @Test
    fun `plain text passes through`() {
        assertEquals("just words", TranscriptText.render("  just words  ", "text/plain"))
    }

    @Test
    fun `unknown type sniffs vtt by header`() {
        val vtt = "WEBVTT\n\n00:00:01.000 --> 00:00:02.000\nHi."
        assertEquals("Hi.", TranscriptText.render(vtt, null))
    }
}
