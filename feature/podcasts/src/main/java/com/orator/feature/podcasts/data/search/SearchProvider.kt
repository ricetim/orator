package com.orator.feature.podcasts.data.search

/** Everything discovery yields; subscribing reuses PodcastRepository.subscribe(feedUrl). */
data class PodcastSearchResult(
    val title: String,
    val author: String?,
    val feedUrl: String,
    val artworkUrl: String?,
)

/** PI rejects requests without a User-Agent; sent on every search request for symmetry. */
internal const val SEARCH_USER_AGENT = "Orator/0.1 (Android podcast player)"

interface SearchProvider {
    val name: String
    suspend fun search(term: String): Result<List<PodcastSearchResult>>
}
