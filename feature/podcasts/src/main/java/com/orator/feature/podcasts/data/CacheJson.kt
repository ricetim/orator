package com.orator.feature.podcasts.data

import com.orator.core.database.EpisodeEntity
import com.orator.core.database.PodcastEntity
import org.json.JSONObject

/** Pretty-printed JSON for the cache tree. org.json ships with Android — no dependency. */
object CacheJson {

    fun showJson(podcast: PodcastEntity): String = JSONObject().apply {
        put("id", podcast.id)
        put("title", podcast.title)
        put("feedUrl", podcast.feedUrl)
        putOpt("author", podcast.author)
        putOpt("description", podcast.description)
        putOpt("artworkUrl", podcast.artworkUrl)
        put("subscribedAtUtc", podcast.subscribedAtUtc)
    }.toString(2)

    fun episodeJson(episode: EpisodeEntity): String = JSONObject().apply {
        put("id", episode.id)
        put("title", episode.title)
        put("pubDateUtc", episode.pubDateUtc)
        put("durationMs", episode.durationMs)
        put("enclosureUrl", episode.enclosureUrl)
    }.toString(2)
}
