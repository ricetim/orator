package com.orator.feature.audiobooks

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.orator.feature.audiobooks.data.AudiobookRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.stateIn
import javax.inject.Inject

/**
 * Results paired with the term they were computed for. The screen owns the text field's state, so
 * without the pairing it could show "No matches" against a term the search hasn't caught up to yet.
 */
data class SearchUi(val term: String, val results: SearchResults)

@HiltViewModel
class AudiobookSearchViewModel @Inject constructor(
    repository: AudiobookRepository,
) : ViewModel() {

    private val term = MutableStateFlow("")

    fun onQueryChange(value: String) {
        term.value = value
    }

    val state: StateFlow<SearchUi> =
        combine(repository.observeBooks(), term) { books, t -> SearchUi(t, BookExplore.search(books, t)) }
            // Searching the whole library on each keystroke is only a few milliseconds, but it has
            // no business sitting between the keypress and the repaint.
            .flowOn(Dispatchers.Default)
            .stateIn(
                viewModelScope,
                SharingStarted.WhileSubscribed(5_000),
                SearchUi("", SearchResults.Empty),
            )
}
