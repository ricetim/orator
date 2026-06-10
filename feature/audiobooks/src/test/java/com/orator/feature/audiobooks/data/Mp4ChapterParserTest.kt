package com.orator.feature.audiobooks.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream

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

    @Test
    fun `chpl with unknown version yields no chapters`() {
        // Seen in the wild (user library): variant chpl boxes with version bytes 0/13/190.
        val file = mp4WithChpl { chpl ->
            chpl.writeByte(190); chpl.write(ByteArray(3)) // version + flags
            chpl.writeInt(0)
            chpl.writeByte(1)
            chpl.writeLong(0); chpl.writeByte(2); chpl.writeBytes("ch")
        }
        assertTrue(Mp4ChapterParser.parse(file.inputStream()).isEmpty())
    }

    @Test
    fun `v1 chpl with non-monotonic starts yields no chapters`() {
        val file = mp4WithChpl { chpl ->
            chpl.writeByte(1); chpl.write(ByteArray(3))
            chpl.writeInt(0)
            chpl.writeByte(2)
            chpl.writeLong(50_000_000); chpl.writeByte(1); chpl.writeBytes("a") // 5 s
            chpl.writeLong(10_000_000); chpl.writeByte(1); chpl.writeBytes("b") // 1 s — out of order
        }
        assertTrue(Mp4ChapterParser.parse(file.inputStream()).isEmpty())
    }

    /** Builds a minimal mp4: moov > udta > chpl with the given body. */
    private fun mp4WithChpl(body: (DataOutputStream) -> Unit): ByteArray {
        val chplBody = ByteArrayOutputStream().also { body(DataOutputStream(it)) }.toByteArray()
        fun box(type: String, payload: ByteArray): ByteArray {
            val out = ByteArrayOutputStream()
            DataOutputStream(out).apply {
                writeInt(payload.size + 8)
                writeBytes(type)
                write(payload)
            }
            return out.toByteArray()
        }
        return box("moov", box("udta", box("chpl", chplBody)))
    }
}
