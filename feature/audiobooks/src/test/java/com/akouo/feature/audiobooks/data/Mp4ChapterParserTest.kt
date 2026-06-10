package com.akouo.feature.audiobooks.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class Mp4ChapterParserTest {

    private fun fixture() = requireNotNull(
        javaClass.classLoader?.getResourceAsStream("fixture.m4b"),
    ) { "fixture.m4b missing from test resources" }

    @Test
    fun `parses Nero chapters from an ffmpeg m4b`() {
        val chapters = Mp4ChapterParser.parse(fixture())

        assertEquals(listOf("Chapter One", "Chapter Two"), chapters.map { it.title })
        assertEquals(0L, chapters[0].startMs)
        assertEquals(4_000L, chapters[1].startMs)
    }

    @Test
    fun `non-mp4 data yields no chapters`() {
        assertTrue(Mp4ChapterParser.parse("definitely not an mp4".byteInputStream()).isEmpty())
    }

    @Test
    fun `empty stream yields no chapters`() {
        assertTrue(Mp4ChapterParser.parse(ByteArray(0).inputStream()).isEmpty())
    }
}
