package com.orator.feature.podcasts.data.search

/** Which provider answered + its results; placeholder UI shows the provider as a diagnostic. */
data class SearchAnswer(val provider: String, val results: List<PodcastSearchResult>)

/** PI first; any failure (including not-configured) silently falls through to iTunes. */
class CompositeSearchProvider(
    private val primary: SearchProvider,
    private val fallback: SearchProvider,
) {
    // distinctBy: feedUrl is the UI's LazyColumn key, and providers can return duplicate rows.
    suspend fun search(term: String): Result<SearchAnswer> =
        primary.search(term).fold(
            onSuccess = {
                Result.success(SearchAnswer(primary.name, it.distinctBy(PodcastSearchResult::feedUrl)))
            },
            onFailure = {
                fallback.search(term).map { results ->
                    SearchAnswer(fallback.name, results.distinctBy(PodcastSearchResult::feedUrl))
                }
            },
        )
}
