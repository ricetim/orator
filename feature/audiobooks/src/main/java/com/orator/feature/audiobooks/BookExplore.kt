package com.orator.feature.audiobooks

import com.orator.core.database.BookEntity
import com.orator.feature.audiobooks.data.NaturalOrder

/**
 * A labelled run of books in AUTHOR/SERIES grouping. For RECENT/TITLE, [BookExplore.group]
 * falls back to a single flat section with an empty header.
 */
data class Section(val header: String, val books: List<BookEntity>)

/** A series/author match with how many books carry it. */
data class NamedHit(val name: String, val count: Int)

/** Multi-category local search output; any list may be empty. */
data class SearchResults(
    val books: List<BookEntity>,
    val series: List<NamedHit>,
    val authors: List<NamedHit>,
) {
    val isEmpty: Boolean get() = books.isEmpty() && series.isEmpty() && authors.isEmpty()

    companion object {
        val Empty = SearchResults(emptyList(), emptyList(), emptyList())
    }
}

/** Pure, Android-free library exploration over BookEntity. Single source of sort/group/search. */
object BookExplore {
    private const val UNKNOWN_AUTHOR = "Unknown author"
    private const val STANDALONE = "Standalone"

    /**
     * "Foundation #2" -> ("Foundation", 2.0); "Name" -> ("Name", null). The marker is the
     * LAST " #"; if what follows it isn't numeric the whole string is the name (null sequence).
     */
    fun parseSeries(stored: String): Pair<String, Double?> {
        val marker = stored.lastIndexOf(" #")
        if (marker < 0) return stored.trim() to null
        val seq = stored.substring(marker + 2).trim().toDoubleOrNull()
            ?: return stored.trim() to null
        return stored.substring(0, marker).trim() to seq
    }

    fun sort(books: List<BookEntity>, mode: BookSortMode): List<BookEntity> = when (mode) {
        BookSortMode.RECENT ->
            books.sortedWith(compareByDescending<BookEntity> { it.addedAtUtc }.thenBy(NaturalOrder) { it.title })
        else -> books.sortedWith(compareBy(NaturalOrder) { it.title })
    }

    fun group(books: List<BookEntity>, mode: BookSortMode): List<Section> = when (mode) {
        BookSortMode.AUTHOR -> {
            val (known, unknown) = books.partition { !it.author.isNullOrBlank() }
            val sections = known.groupBy { it.author!!.trim() }
                .entries.sortedWith(compareBy(NaturalOrder) { it.key })
                .map { (name, group) -> Section(name, group.sortedWith(compareBy(NaturalOrder) { it.title })) }
            if (unknown.isEmpty()) sections
            else sections + Section(UNKNOWN_AUTHOR, unknown.sortedWith(compareBy(NaturalOrder) { it.title }))
        }
        BookSortMode.SERIES -> {
            val (inSeries, standalone) = books.partition { !it.series.isNullOrBlank() }
            // Parse once per book rather than once per comparison.
            val parsed = inSeries.map { parseSeries(it.series!!) to it }
            val sections = parsed.groupBy({ it.first.first }, { it })
                .entries.sortedWith(compareBy(NaturalOrder) { it.key })
                .map { (name, group) ->
                    Section(name, group.sortedWith(compareBy(nullsLast()) { it.first.second }).map { it.second })
                }
            if (standalone.isEmpty()) sections
            else sections + Section(STANDALONE, standalone.sortedWith(compareBy(NaturalOrder) { it.title }))
        }
        else -> listOf(Section("", sort(books, mode)))
    }

    fun search(books: List<BookEntity>, term: String): SearchResults {
        val q = term.trim()
        if (q.isEmpty()) return SearchResults.Empty

        val titleHits = books.filter { it.title.contains(q, ignoreCase = true) }
            .sortedWith(compareBy(NaturalOrder) { it.title })

        val seriesHits = books.mapNotNull { it.series?.let { s -> parseSeries(s).first } }
            .filter { it.contains(q, ignoreCase = true) }
            .groupingBy { it }.eachCount()
            .map { (name, count) -> NamedHit(name, count) }
            .sortedWith(compareBy(NaturalOrder) { it.name })

        val authorHits = books.mapNotNull { it.author?.takeIf { a -> a.isNotBlank() } }
            .filter { it.contains(q, ignoreCase = true) }
            .groupingBy { it.trim() }.eachCount()
            .map { (name, count) -> NamedHit(name, count) }
            .sortedWith(compareBy(NaturalOrder) { it.name })

        return SearchResults(titleHits, seriesHits, authorHits)
    }

    fun filterSeries(books: List<BookEntity>, name: String): List<BookEntity> =
        books.mapNotNull { book -> book.series?.let { parseSeries(it) to book } }
            .filter { it.first.first == name }
            .sortedWith(compareBy(nullsLast()) { it.first.second })
            .map { it.second }

    fun filterAuthor(books: List<BookEntity>, name: String): List<BookEntity> =
        books.filter { it.author?.trim() == name }
            .sortedWith(compareBy(NaturalOrder) { it.title })
}
