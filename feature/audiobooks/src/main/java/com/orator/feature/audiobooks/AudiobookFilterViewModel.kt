package com.orator.feature.audiobooks

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.orator.core.database.BookEntity
import com.orator.feature.audiobooks.data.AudiobookRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import javax.inject.Inject

@HiltViewModel
class AudiobookFilterViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    repository: AudiobookRepository,
) : ViewModel() {

    // Nav decodes the encoded {value} segment before it lands here. Both args are required by the
    // route pattern, so a destination that matched at all has them.
    private val type: String = checkNotNull(savedStateHandle["type"])

    /** The series or author being filtered on; also the screen's title. */
    val filterName: String = checkNotNull(savedStateHandle["value"])

    val books: StateFlow<List<BookEntity>> =
        repository.observeBooks()
            .map { all ->
                if (type == FilterBySeries) {
                    BookExplore.filterSeries(all, filterName)
                } else {
                    BookExplore.filterAuthor(all, filterName)
                }
            }
            .flowOn(Dispatchers.Default)
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())
}
