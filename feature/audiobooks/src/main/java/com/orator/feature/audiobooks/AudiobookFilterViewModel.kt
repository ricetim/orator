package com.orator.feature.audiobooks

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.orator.core.database.BookEntity
import com.orator.feature.audiobooks.data.AudiobookRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import javax.inject.Inject

@HiltViewModel
class AudiobookFilterViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val repository: AudiobookRepository,
) : ViewModel() {

    // Nav decodes the encoded {value} segment before it lands here.
    private val type: String = checkNotNull(savedStateHandle["type"])
    val value: String = checkNotNull(savedStateHandle["value"])

    val books: StateFlow<List<BookEntity>> =
        repository.observeBooks().map { all ->
            if (type == "series") BookExplore.filterSeries(all, value) else BookExplore.filterAuthor(all, value)
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())
}
