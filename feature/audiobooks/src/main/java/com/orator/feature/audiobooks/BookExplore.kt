package com.orator.feature.audiobooks

import com.orator.core.database.BookEntity
import com.orator.feature.audiobooks.data.NaturalOrder

/** A labelled run of books in AUTHOR/SERIES grouping. */
data class Section(val header: String, val books: List<BookEntity>)

/** A series/author match with how many books carry it. */
data class NamedHit(val name: String, val count: Int)

/** Multi-category local search output; any list may be empty. */
data class SearchResults(
    val books: List<BookEntity>,
    val series: List<NamedHit>,
    val authors: List<NamedHit>,
)

/** Pure, Android-free library exploration over BookEntity. Single source of sort/group/search. */
object BookExplore {
    private const val UNKNOWN_AUTHOR = "Unknown author"
    private const val STANDALONE = "Standalone"

    /** "Foundation #2" -> ("Foundation", 2.0); "Name" -> ("Name", null). */
    fun parseSeries(stored: String): Pair<String, Double?> {
        val marker = stored.lastIndexOf(" #")
        if (marker < 0) return stored.trim() to null
        val name = stored.substring(0, marker).trim()
        val seq = stored.substring(marker + 2).trim().toDoubleOrNull()
        return name to seq
    }

    fun sort(books: List<BookEntity>, mode: BookSortMode): List<BookEntity> = when (mode) {
        BookSortMode.RECENT -> books.sortedByDescending { it.addedAtUtc }
        else -> books.sortedWith(compareBy(NaturalOrder) { it.title })
    }

    fun group(books: List<BookEntity>, mode: BookSortMode): List<Section> = when (mode) {
        BookSortMode.AUTHOR -> {
            val (known, unknown) = books.partition { !it.author.isNullOrBlank() }
            val sections = known.groupBy { it.author!!.trim() }
                .toSortedMap(NaturalOrder)
                .map { (name, group) -> Section(name, group.sortedWith(compareBy(NaturalOrder) { it.title })) }
            if (unknown.isEmpty()) sections
            else sections + Section(UNKNOWN_AUTHOR, unknown.sortedWith(compareBy(NaturalOrder) { it.title }))
        }
        BookSortMode.SERIES -> {
            val (inSeries, standalone) = books.partition { !it.series.isNullOrBlank() }
            val byName = inSeries.groupBy { parseSeries(it.series!!).first }
            val sections = byName.toSortedMap(NaturalOrder).map { (name, group) ->
                Section(name, group.sortedWith(
                    compareBy(nullsLast()) { parseSeries(it.series!!).second },
                ))
            }
            if (standalone.isEmpty()) sections
            else sections + Section(STANDALONE, standalone.sortedWith(compareBy(NaturalOrder) { it.title }))
        }
        else -> listOf(Section("", sort(books, mode)))
    }
}
