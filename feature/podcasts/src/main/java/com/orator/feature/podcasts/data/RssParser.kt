package com.orator.feature.podcasts.data

import org.xmlpull.v1.XmlPullParser
import org.xmlpull.v1.XmlPullParserFactory
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale

data class ParsedFeed(
    val title: String,
    val author: String?,
    val description: String?,
    val artworkUrl: String?,
    val items: List<ParsedItem>,
)

data class ParsedItem(
    val guid: String?,
    val title: String,
    val pubDateUtc: Long,
    val durationMs: Long,
    val enclosureUrl: String,
    val enclosureType: String?,
    val showNotesHtml: String?,
    val transcriptUrl: String? = null,
    val transcriptType: String? = null,
)

/**
 * Hand-rolled, tolerant RSS 2.0 parser (the chpl lesson: real-world data is messy — skip bad
 * items, never abort the feed). Namespace prefixes vary by feed, so tags are matched on the
 * local name after ':'.
 */
object RssParser {

    fun parse(xml: String): ParsedFeed? = try {
        val parser = XmlPullParserFactory.newInstance().newPullParser()
        parser.setInput(xml.reader())

        var channelTitle: String? = null
        var author: String? = null
        var description: String? = null
        var artworkUrl: String? = null
        val items = mutableListOf<ParsedItem>()
        var inItem = false

        // item-in-progress fields
        var iGuid: String? = null
        var iTitle: String? = null
        var iPubDate = 0L
        var iDuration = 0L
        var iEnclosureUrl: String? = null
        var iEnclosureType: String? = null
        var iDescription: String? = null
        var iContentEncoded: String? = null
        var iTranscripts = mutableListOf<Pair<String, String?>>()

        var event = parser.eventType
        while (event != XmlPullParser.END_DOCUMENT) {
            when (event) {
                XmlPullParser.START_TAG -> {
                    val name = parser.name.substringAfterLast(':')
                    if (inItem) {
                        when (name) {
                            "title" -> iTitle = readText(parser)
                            "guid" -> iGuid = readText(parser)?.takeIf { it.isNotBlank() }
                            "pubDate" -> iPubDate = parseDate(readText(parser))
                            "duration" -> iDuration = parseDuration(readText(parser))
                            "enclosure" -> {
                                iEnclosureUrl = parser.getAttributeValue(null, "url")
                                iEnclosureType = parser.getAttributeValue(null, "type")
                            }
                            "description" -> iDescription = readText(parser)
                            "encoded" -> iContentEncoded = readText(parser)
                            "transcript" -> parser.getAttributeValue(null, "url")
                                ?.takeIf { it.isNotBlank() }
                                ?.let { iTranscripts.add(it to parser.getAttributeValue(null, "type")) }
                        }
                    } else {
                        when (name) {
                            "item" -> {
                                inItem = true
                                iGuid = null; iTitle = null; iPubDate = 0L; iDuration = 0L
                                iEnclosureUrl = null; iEnclosureType = null
                                iDescription = null; iContentEncoded = null
                                iTranscripts = mutableListOf()
                            }
                            "title" -> if (channelTitle == null) channelTitle = readText(parser)
                            "author" -> if (author == null) author = readText(parser)
                            "description" -> if (description == null) description = readText(parser)
                            "image" -> parser.getAttributeValue(null, "href")
                                ?.let { artworkUrl = it } // <itunes:image href=.../>
                            "url" -> if (artworkUrl == null) artworkUrl = readText(parser) // <image><url>
                        }
                    }
                }
                XmlPullParser.END_TAG -> {
                    if (parser.name.substringAfterLast(':') == "item" && inItem) {
                        inItem = false
                        val title = iTitle
                        val enclosure = iEnclosureUrl
                        if (!title.isNullOrBlank() && !enclosure.isNullOrBlank()) {
                            val transcript = pickTranscript(iTranscripts)
                            items += ParsedItem(
                                guid = iGuid,
                                title = title,
                                pubDateUtc = iPubDate,
                                durationMs = iDuration,
                                enclosureUrl = enclosure,
                                enclosureType = iEnclosureType,
                                showNotesHtml = iContentEncoded ?: iDescription,
                                transcriptUrl = transcript?.first,
                                transcriptType = transcript?.second,
                            )
                        }
                    }
                }
            }
            event = parser.next()
        }

        channelTitle?.takeIf { it.isNotBlank() }?.let { t ->
            ParsedFeed(t, author, description, artworkUrl, items)
        }
    } catch (e: Exception) {
        null // garbage in, null out — callers report a per-feed failure
    }

    /** Concatenates TEXT/CDSECT until the element closes; tolerates nested tags inside. */
    private fun readText(parser: XmlPullParser): String? {
        val sb = StringBuilder()
        var depth = 1
        while (depth > 0) {
            when (parser.next()) {
                XmlPullParser.TEXT -> sb.append(parser.text)
                XmlPullParser.START_TAG -> depth++
                XmlPullParser.END_TAG -> depth--
                XmlPullParser.END_DOCUMENT -> return sb.toString().trim().ifEmpty { null }
            }
        }
        return sb.toString().trim().ifEmpty { null }
    }

    /** Spec order: vtt > srt/subrip > plain > json; unknown types only when nothing better. */
    private val TRANSCRIPT_PREFERENCE =
        listOf("text/vtt", "application/srt", "application/x-subrip", "text/plain", "application/json")

    private fun pickTranscript(candidates: List<Pair<String, String?>>): Pair<String, String?>? =
        candidates.minByOrNull { (_, type) ->
            TRANSCRIPT_PREFERENCE.indexOf(type.orEmpty())
                .let { if (it == -1) TRANSCRIPT_PREFERENCE.size else it }
        }

    private val RFC1123 = DateTimeFormatter.RFC_1123_DATE_TIME
    private val RFC1123_LENIENT =
        DateTimeFormatter.ofPattern("EEE, d MMM yyyy HH:mm:ss zzz", Locale.US)

    private fun parseDate(text: String?): Long {
        if (text.isNullOrBlank()) return 0L
        for (fmt in listOf(RFC1123, RFC1123_LENIENT)) {
            try {
                return ZonedDateTime.parse(text.trim(), fmt).toInstant().toEpochMilli()
            } catch (_: Exception) { /* try next */ }
        }
        return 0L
    }

    /** itunes:duration is "HH:MM:SS", "MM:SS", or bare seconds — and some feeds emit decimals ("5400.0"). */
    private fun parseDuration(text: String?): Long {
        if (text.isNullOrBlank()) return 0L
        val parts = text.trim().split(':').map { it.toDoubleOrNull() ?: return 0L }
        return when (parts.size) {
            1 -> (parts[0] * 1000).toLong()
            2 -> ((parts[0] * 60 + parts[1]) * 1000).toLong()
            3 -> (((parts[0] * 60 + parts[1]) * 60 + parts[2]) * 1000).toLong()
            else -> 0L
        }
    }
}
