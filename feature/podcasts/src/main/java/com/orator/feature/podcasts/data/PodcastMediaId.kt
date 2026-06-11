package com.orator.feature.podcasts.data

/** Routes service callbacks (position, speed) back to an episode row. Format: "podcast/<episodeId>". */
object PodcastMediaId {
    private const val PREFIX = "podcast"

    fun encode(episodeId: String): String = "$PREFIX/$episodeId"

    fun parse(mediaId: String): String? {
        val parts = mediaId.split('/')
        if (parts.size != 2 || parts[0] != PREFIX || parts[1].isBlank()) return null
        return parts[1]
    }
}
