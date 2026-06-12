package com.orator.feature.podcasts

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.orator.feature.podcasts.data.PodcastRepository
import com.orator.feature.podcasts.data.search.CompositeSearchProvider
import com.orator.feature.podcasts.data.search.PodcastSearchResult
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

/** A search term that is actually a feed URL gets a direct-subscribe row instead of a search. */
internal fun looksLikeFeedUrl(term: String): Boolean =
    term.startsWith("http://") || term.startsWith("https://")

@HiltViewModel
class SearchViewModel @Inject constructor(
    private val searchProvider: CompositeSearchProvider,
    private val repository: PodcastRepository,
) : ViewModel() {

    data class UiState(
        val searching: Boolean = false,
        val provider: String? = null,
        val results: List<PodcastSearchResult> = emptyList(),
        val error: String? = null,
        val subscribedFeeds: Set<String> = emptySet(),
        /** Set when the entered term is a URL — UI shows a direct-subscribe row. */
        val directUrl: String? = null,
    )

    private val _state = MutableStateFlow(UiState())
    val state: StateFlow<UiState> = _state.asStateFlow()

    fun onSearch(term: String) {
        if (term.isBlank()) return
        val trimmed = term.trim()
        if (looksLikeFeedUrl(trimmed)) {
            _state.value = _state.value.copy(
                searching = false,
                error = null,
                provider = null,
                results = emptyList(),
                directUrl = trimmed,
            )
            return
        }
        viewModelScope.launch {
            _state.value = _state.value.copy(searching = true, error = null, directUrl = null)
            searchProvider.search(trimmed).fold(
                onSuccess = { answer ->
                    _state.value = _state.value.copy(
                        searching = false,
                        provider = answer.provider,
                        results = answer.results,
                    )
                },
                onFailure = { e ->
                    _state.value = _state.value.copy(
                        searching = false,
                        error = "Search failed: ${e.message}",
                    )
                },
            )
        }
    }

    fun onSubscribe(result: PodcastSearchResult) {
        viewModelScope.launch {
            repository.subscribe(result.feedUrl).onSuccess {
                _state.value = _state.value.copy(
                    subscribedFeeds = _state.value.subscribedFeeds + result.feedUrl,
                )
            }
        }
    }

    fun onSubscribeUrl(url: String) {
        viewModelScope.launch {
            repository.subscribe(url).fold(
                onSuccess = {
                    _state.value = _state.value.copy(
                        subscribedFeeds = _state.value.subscribedFeeds + url,
                    )
                },
                onFailure = { e ->
                    _state.value = _state.value.copy(error = "Subscribe failed: ${e.message}")
                },
            )
        }
    }
}
