package com.orator.feature.audiobooks

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.orator.feature.audiobooks.data.AudiobookRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import javax.inject.Inject

@HiltViewModel
class AudiobookSearchViewModel @Inject constructor(
    private val repository: AudiobookRepository,
) : ViewModel() {

    private val term = MutableStateFlow("")
    val query: StateFlow<String> = term.asStateFlow()

    fun onQueryChange(value: String) {
        term.value = value
    }

    val results: StateFlow<SearchResults> =
        combine(repository.observeBooks(), term) { books, t -> BookExplore.search(books, t) }
            .stateIn(
                viewModelScope, SharingStarted.WhileSubscribed(5_000),
                SearchResults(emptyList(), emptyList(), emptyList()),
            )
}
