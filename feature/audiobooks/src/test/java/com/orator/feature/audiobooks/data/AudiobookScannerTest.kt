package com.orator.feature.audiobooks.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
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
    fun `single m4b is a single-file book`() {
        val books = AudiobookScanner.scan(dir("root", dir("Book", file("a.m4b"))))
        assertTrue(books.single() is ScannedBook.SingleFile)
    }

    @Test
    fun `many m4b in one dir is one multi-file book, naturally sorted`() {
        val books = AudiobookScanner.scan(
            dir("root", dir("Book", file("part (10).m4b"), file("part (2).m4b"), file("part (1).m4b"))),
        )
        val mf = books.single() as ScannedBook.MultiFile
        assertEquals("Book", mf.title)
        assertEquals(listOf("part (1).m4b", "part (2).m4b", "part (10).m4b"), mf.files.map { it.name })
    }

    @Test
    fun `a directory of mp3s becomes one multi-file book, naturally ordered`() {
        val root = dir("root", dir("My Book", file("Track 10.mp3"), file("Track 2.mp3"), file("Track 1.mp3")))

        val book = AudiobookScanner.scan(root).single() as ScannedBook.MultiFile

        assertEquals("My Book", book.title)
        assertEquals(listOf("Track 1.mp3", "Track 2.mp3", "Track 10.mp3"), book.files.map { it.name })
    }

    @Test
    fun `mixed m4b and mp3 in one dir are grouped, naturally sorted`() {
        val mf = AudiobookScanner.scan(dir("root", dir("Book", file("a.m4b"), file("b.mp3"))))
            .single() as ScannedBook.MultiFile
        assertEquals(2, mf.files.size)
    }

    @Test
    fun `non-audio files are ignored`() {
        val root = dir(
            "root",
            dir("My Book", file("Track 1.mp3"), file("Track 2.mp3"), file("cover.jpg"), file("notes.txt")),
        )

        val book = AudiobookScanner.scan(root).single() as ScannedBook.MultiFile

        assertEquals(2, book.files.size)
    }

    @Test
    fun `nested single-file books are found by recursion`() {
        val root = dir("root", file("Solo Book.m4b"), dir("nested", file("Deep Book.m4b")))

        val books = AudiobookScanner.scan(root)

        assertEquals(listOf("Deep Book", "Solo Book"), books.map { it.title }.sorted())
        assertTrue(books.all { it is ScannedBook.SingleFile })
    }

    @Test
    fun `nested author then multi-file book`() {
        val books = AudiobookScanner.scan(dir("root", dir("Author", dir("Book", file("a.m4b"), file("b.m4b")))))
        assertTrue(books.single() is ScannedBook.MultiFile)
    }

    @Test
    fun `a stray file beside a subfolder still finds the subfolder`() {
        // New rule: always recurse. My Book's lone direct mp3 is one book; extras/ is another.
        val root = dir("root", dir("My Book", file("Track 1.mp3"), dir("extras", file("bonus.mp3"))))

        val books = AudiobookScanner.scan(root)

        assertEquals(2, books.size)
        assertTrue(books.all { it is ScannedBook.SingleFile })
    }

    @Test
    fun `empty tree yields nothing`() {
        assertEquals(emptyList<ScannedBook>(), AudiobookScanner.scan(dir("root")))
    }
}
