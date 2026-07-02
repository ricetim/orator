package com.orator.feature.audiobooks

import com.orator.core.database.BookEntity
import com.orator.core.database.SourceKind
import com.orator.core.model.BookOrigin
import org.junit.Assert.assertEquals
import org.junit.Test

class BookExploreTest {
    private fun book(
        id: String, title: String, author: String? = null, series: String? = null,
        added: Long = 0,
    ) = BookEntity(
        id = id, title = title, author = author, coverPath = null, sourceUri = "",
        sourceKind = SourceKind.SINGLE_FILE, durationMs = 0, addedAtUtc = added,
        origin = BookOrigin.ABS, series = series,
    )

    @Test fun `parseSeries splits name and numeric sequence`() {
        assertEquals("Foundation" to 2.0, BookExplore.parseSeries("Foundation #2"))
    }

    @Test fun `parseSeries handles decimal sequence`() {
        assertEquals("Foundation" to 2.5, BookExplore.parseSeries("Foundation #2.5"))
    }

    @Test fun `parseSeries with no marker has null sequence`() {
        assertEquals("Standalone Name" to null, BookExplore.parseSeries("Standalone Name"))
    }

    @Test fun `sort RECENT orders by addedAt descending`() {
        val out = BookExplore.sort(
            listOf(book("a", "A", added = 100), book("b", "B", added = 300), book("c", "C", added = 200)),
            BookSortMode.RECENT,
        )
        assertEquals(listOf("b", "c", "a"), out.map { it.id })
    }

    @Test fun `sort TITLE is case-insensitive natural order`() {
        val out = BookExplore.sort(
            listOf(book("x", "Book 10"), book("y", "book 2"), book("z", "Apple")),
            BookSortMode.TITLE,
        )
        assertEquals(listOf("z", "y", "x"), out.map { it.id })   // Apple, book 2, Book 10
    }

    @Test fun `group AUTHOR sections are alphabetical with Unknown last`() {
        val sections = BookExplore.group(
            listOf(
                book("a", "A", author = "Zadie"),
                book("b", "B", author = null),
                book("c", "C", author = "Adichie"),
            ),
            BookSortMode.AUTHOR,
        )
        assertEquals(listOf("Adichie", "Zadie", "Unknown author"), sections.map { it.header })
    }

    @Test fun `group SERIES sub-sorts by sequence, Standalone last`() {
        val sections = BookExplore.group(
            listOf(
                book("a", "Second", series = "Foundation #2"),
                book("b", "Loner", series = null),
                book("c", "First", series = "Foundation #1"),
            ),
            BookSortMode.SERIES,
        )
        assertEquals(listOf("Foundation", "Standalone"), sections.map { it.header })
        assertEquals(listOf("c", "a"), sections.first().books.map { it.id })  // #1 before #2
    }
}
