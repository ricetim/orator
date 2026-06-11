package com.orator.feature.podcasts.data.search

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.net.URLEncoder

/** Keyless fallback; the only required field is feedUrl — rows without one are useless. */
class ItunesSearchProvider(
    private val client: OkHttpClient,
    private val baseUrl: String = "https://itunes.apple.com",
) : SearchProvider {

    override val name: String = "iTunes"

    override suspend fun search(term: String): Result<List<PodcastSearchResult>> =
        withContext(Dispatchers.IO) {
            runCatching {
                val url = "$baseUrl/search?media=podcast&limit=25&term=" +
                    URLEncoder.encode(term, "UTF-8")
                val request = Request.Builder().url(url)
                    .header("User-Agent", SEARCH_USER_AGENT)
                    .build()
                client.newCall(request).execute().use { response ->
                    require(response.isSuccessful) { "HTTP ${response.code}" }
                    val results = JSONObject(response.body?.string().orEmpty())
                        .optJSONArray("results") ?: return@use emptyList()
                    buildList {
                        for (i in 0 until results.length()) {
                            val row = results.getJSONObject(i)
                            val feedUrl = row.optString("feedUrl")
                                .takeIf { it.isNotBlank() } ?: continue
                            add(
                                PodcastSearchResult(
                                    title = row.optString("collectionName"),
                                    author = row.optString("artistName").takeIf { it.isNotBlank() },
                                    feedUrl = feedUrl,
                                    artworkUrl = row.optString("artworkUrl600").takeIf { it.isNotBlank() },
                                ),
                            )
                        }
                    }
                }
            }
        }
}
