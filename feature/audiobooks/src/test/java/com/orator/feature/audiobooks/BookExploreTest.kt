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

    @Test fun `search matches title series and author case-insensitively`() {
        val books = listOf(
            book("a", "Redwall", author = "Brian Jacques", series = "Redwall #1"),
            book("b", "Mossflower", author = "Brian Jacques", series = "Redwall #2"),
            book("c", "Dune", author = "Herbert"),
        )
        val r = BookExplore.search(books, "red")
        assertEquals(listOf("a"), r.books.map { it.id })                 // title contains
        assertEquals(listOf(NamedHit("Redwall", 2)), r.series)           // distinct + count
        assertEquals(emptyList<NamedHit>(), r.authors)                   // no author matches
    }

    @Test fun `blank search term yields all-empty results`() {
        val r = BookExplore.search(listOf(book("a", "A")), "   ")
        assertEquals(0, r.books.size + r.series.size + r.authors.size)
    }

    @Test fun `filterSeries returns that series ordered by sequence`() {
        val books = listOf(
            book("a", "Two", series = "Redwall #2"),
            book("b", "One", series = "Redwall #1"),
            book("c", "Other", series = "Dune #1"),
        )
        assertEquals(listOf("b", "a"), BookExplore.filterSeries(books, "Redwall").map { it.id })
    }

    @Test fun `filterAuthor returns that author ordered by title`() {
        val books = listOf(
            book("a", "Beta", author = "Jacques"),
            book("b", "Alpha", author = "Jacques"),
            book("c", "X", author = "Herbert"),
        )
        assertEquals(listOf("b", "a"), BookExplore.filterAuthor(books, "Jacques").map { it.id })
    }
}
