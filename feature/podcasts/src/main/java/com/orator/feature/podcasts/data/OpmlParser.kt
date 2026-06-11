package com.orator.feature.podcasts.data

import org.xmlpull.v1.XmlPullParser
import org.xmlpull.v1.XmlPullParserFactory

data class OpmlFeed(val title: String, val xmlUrl: String)

/** Any <outline> with an xmlUrl is a feed, at any nesting depth; everything else is a folder. */
object OpmlParser {

    fun parse(xml: String): List<OpmlFeed> = try {
        val parser = XmlPullParserFactory.newInstance().newPullParser()
        parser.setInput(xml.reader())
        val feeds = mutableListOf<OpmlFeed>()
        var event = parser.eventType
        while (event != XmlPullParser.END_DOCUMENT) {
            if (event == XmlPullParser.START_TAG && parser.name == "outline") {
                val url = parser.getAttributeValue(null, "xmlUrl")
                if (!url.isNullOrBlank()) {
                    val title = parser.getAttributeValue(null, "title")
                        ?: parser.getAttributeValue(null, "text")
                        ?: url
                    feeds += OpmlFeed(title, url)
                }
            }
            event = parser.next()
        }
        feeds
    } catch (e: Exception) {
        emptyList()
    }
}
