package com.orator.feature.audiobooks.data

import org.junit.Assert.assertEquals
import org.junit.Test

class ChapterAssemblerTest {

    @Test
    fun `flattens chpl across files contiguously`() {
        val input = listOf(
            ChapterAssembler.FileChapters(
                fileUri = "A", durationMs = 3000,
                marks = listOf(Mp4ChapterParser.Chapter("Intro", 0), Mp4ChapterParser.Chapter("Ch1", 1000)),
                fallbackTitle = "A",
            ),
            ChapterAssembler.FileChapters(
                fileUri = "B", durationMs = 1500, marks = emptyList(), fallbackTitle = "B",
            ),
        )

        val out = ChapterAssembler.assemble("book", input)

        assertEquals(listOf(0, 1, 2), out.map { it.chapterIndex })
        assertEquals(listOf("A", "A", "B"), out.map { it.fileUri })
        assertEquals(listOf(0L, 1000L, 0L), out.map { it.startMs })
        assertEquals(listOf(1000L, 2000L, 1500L), out.map { it.durationMs })
        assertEquals(listOf("Intro", "Ch1", "B"), out.map { it.title })
    }

    @Test
    fun `anchors first chapter of a file to zero when chpl starts late`() {
        val input = listOf(
            ChapterAssembler.FileChapters(
                fileUri = "A", durationMs = 2000,
                marks = listOf(Mp4ChapterParser.Chapter("Late", 500)), fallbackTitle = "A",
            ),
        )

        val out = ChapterAssembler.assemble("book", input)

        assertEquals(0L, out[0].startMs)       // anchored to file start
        assertEquals(2000L, out[0].durationMs) // tiles the whole file
    }

    @Test
    fun `empty file list yields no chapters`() {
        assertEquals(emptyList<Any>(), ChapterAssembler.assemble("book", emptyList()))
    }
}
