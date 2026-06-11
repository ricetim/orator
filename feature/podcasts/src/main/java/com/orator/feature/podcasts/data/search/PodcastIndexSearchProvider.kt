package com.orator.feature.podcasts.data.search

import com.orator.feature.podcasts.BuildConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.net.URLEncoder
import java.security.MessageDigest

/**
 * Podcast Index `search/byterm` with the documented auth scheme: SHA-1(key+secret+date) in
 * the Authorization header. ToS note: this is the ONLY PI endpoint the app calls, always
 * user-initiated — refreshes go straight to publishers' feeds, never through PI.
 */
class PodcastIndexSearchProvider(
    private val client: OkHttpClient,
    private val key: String = BuildConfig.PODCASTINDEX_KEY,
    private val secret: String = BuildConfig.PODCASTINDEX_SECRET,
    private val baseUrl: String = "https://api.podcastindex.org",
    private val epochSeconds: () -> Long = { System.currentTimeMillis() / 1000 },
) : SearchProvider {

    override val name: String = "Podcast Index"

    override suspend fun search(term: String): Result<List<PodcastSearchResult>> =
        withContext(Dispatchers.IO) {
            if (key.isBlank() || secret.isBlank()) {
                return@withContext Result.failure(IllegalStateException("Podcast Index not configured"))
            }
            runCatching {
                val date = epochSeconds().toString()
                val url = "$baseUrl/api/1.0/search/byterm?max=25&q=" +
                    URLEncoder.encode(term, "UTF-8")
                val request = Request.Builder().url(url)
                    .header("X-Auth-Key", key)
                    .header("X-Auth-Date", date)
                    .header("Authorization", sha1Hex(key + secret + date))
                    .header("User-Agent", SEARCH_USER_AGENT)
                    .build()
                client.newCall(request).execute().use { response ->
                    require(response.isSuccessful) { "HTTP ${response.code}" }
                    val feeds = JSONObject(response.body?.string().orEmpty())
                        .optJSONArray("feeds") ?: return@use emptyList()
                    buildList {
                        for (i in 0 until feeds.length()) {
                            val row = feeds.getJSONObject(i)
                            val feedUrl = row.optString("url").takeIf { it.isNotBlank() } ?: continue
                            add(
                                PodcastSearchResult(
                                    title = row.optString("title"),
                                    author = row.optString("author").takeIf { it.isNotBlank() },
                                    feedUrl = feedUrl,
                                    artworkUrl = row.optString("artwork").takeIf { it.isNotBlank() }
                                        ?: row.optString("image").takeIf { it.isNotBlank() },
                                ),
                            )
                        }
                    }
                }
            }
        }

    companion object {
        fun sha1Hex(input: String): String =
            MessageDigest.getInstance("SHA-1").digest(input.toByteArray())
                .joinToString("") { "%02x".format(it) }
    }
}
