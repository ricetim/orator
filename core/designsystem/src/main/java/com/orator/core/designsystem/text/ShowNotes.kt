package com.orator.core.designsystem.text

import androidx.core.text.HtmlCompat

/**
 * Show notes for the placeholder UI: feed HTML → plain text + tappable timestamp spans.
 * Timestamps refer to the ORIGINAL (unclipped) timeline; callers subtract clipIntroMs.
 */
object ShowNotes {

    data class TimestampLink(val startIndex: Int, val endIndex: Int, val positionMs: Long)
    data class Rendered(val text: String, val links: List<TimestampLink>)

    // hh:mm:ss or m:ss / mm:ss; minutes and seconds must be valid base-60 fields. The trailing
    // guards reject longer digit runs and decimals like "12:34.5" while still matching a
    // timestamp followed by a sentence-final period.
    private val TIMESTAMP = Regex("""(?<![\d:.\-])(?:(\d{1,2}):)?([0-5]?\d):([0-5]\d)(?![\d:])(?!\.\d)""")

    fun render(html: String): Rendered {
        val text = HtmlCompat.fromHtml(html, HtmlCompat.FROM_HTML_MODE_COMPACT)
            .toString().trim()
        val links = TIMESTAMP.findAll(text).map { match ->
            val hours = match.groupValues[1].toLongOrNull() ?: 0L
            val minutes = match.groupValues[2].toLong()
            val seconds = match.groupValues[3].toLong()
            TimestampLink(
                startIndex = match.range.first,
                endIndex = match.range.last + 1,
                positionMs = ((hours * 60 + minutes) * 60 + seconds) * 1000,
            )
        }.toList()
        return Rendered(text, links)
    }
}
