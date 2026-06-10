package com.akouo.feature.audiobooks.data

import org.junit.Assert.assertEquals
import org.junit.Test

class AudiobookScannerTest {

    private fun file(name: String) = FakeNode(name, isDirectory = false)
    private fun dir(name: String, vararg children: DocumentNode) =
        FakeNode(name, isDirectory = true, childNodes = children.toList())

    private class FakeNode(
        override val name: String,
        override val isDirectory: Boolean,
        private val childNodes: List<DocumentNode> = emptyList(),
        parentPath: String = "tree:/",
    ) : DocumentNode {
        override val uri: String = "$parentPath$name"
        override fun children(): List<DocumentNode> = childNodes
    }

    @Test
    fun `m4b files become books wherever they sit`() {
        val root = dir("root", file("Solo Book.m4b"), dir("nested", file("Deep Book.m4b")))

        val books = AudiobookScanner.scan(root)

        assertEquals(listOf("Deep Book", "Solo Book"), books.map { it.title }.sorted())
    }

    @Test
    fun `a directory of mp3s becomes one book with naturally ordered files`() {
        val root = dir("root", dir("My Book", file("Track 10.mp3"), file("Track 2.mp3"), file("Track 1.mp3")))

        val books = AudiobookScanner.scan(root)

        val book = books.single() as ScannedBook.Mp3Collection
        assertEquals("My Book", book.title)
        assertEquals(listOf("Track 1.mp3", "Track 2.mp3", "Track 10.mp3"), book.files.map { it.name })
    }

    @Test
    fun `non-audio files are ignored`() {
        val root = dir("root", dir("My Book", file("Track 1.mp3"), file("cover.jpg"), file("notes.txt")))

        val book = AudiobookScanner.scan(root).single() as ScannedBook.Mp3Collection

        assertEquals(1, book.files.size)
    }

    @Test
    fun `a directory with direct mp3s is not recursed further`() {
        // Mixed layout: the dir is claimed as one book from its direct mp3s; subdirs are skipped.
        val root = dir("root", dir("My Book", file("Track 1.mp3"), dir("extras", file("bonus.mp3"))))

        val books = AudiobookScanner.scan(root)

        assertEquals(1, books.size)
    }

    @Test
    fun `empty tree yields nothing`() {
        assertEquals(emptyList<ScannedBook>(), AudiobookScanner.scan(dir("root")))
    }
}
