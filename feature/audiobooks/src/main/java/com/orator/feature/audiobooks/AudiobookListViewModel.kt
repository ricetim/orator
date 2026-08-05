package com.orator.feature.audiobooks

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.orator.core.database.BookEntity
import com.orator.feature.audiobooks.data.AudiobookRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

/** What the grid renders: a flat list (RECENT/TITLE) or labelled sections (AUTHOR/SERIES). */
sealed interface LibraryView {
    data class Flat(val books: List<BookEntity>) : LibraryView
    data class Sectioned(val sections: List<Section>) : LibraryView
}

@HiltViewModel
class AudiobookListViewModel @Inject constructor(
    private val repository: AudiobookRepository,
) : ViewModel() {

    val hasFolder: StateFlow<Boolean> = repository.treeUri
        .map { it != null }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), false)

    val sortMode: StateFlow<BookSortMode> = repository.sortMode
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), BookSortMode.RECENT)

    // Combined against the sortMode StateFlow rather than repository.sortMode: the StateFlow
    // already has a value, so `view` emits as soon as Room does instead of waiting on a DataStore
    // disk read. That keeps the screen's empty-state check (which reads `view`) honest on cold
    // start, at the cost of one frame in RECENT order before a persisted mode loads.
    val view: StateFlow<LibraryView> =
        combine(repository.observeBooks(), sortMode) { books, mode ->
            when (mode) {
                BookSortMode.RECENT, BookSortMode.TITLE -> LibraryView.Flat(BookExplore.sort(books, mode))
                BookSortMode.AUTHOR, BookSortMode.SERIES -> LibraryView.Sectioned(BookExplore.group(books, mode))
            }
        }
            // Sorting and grouping the whole library has no business running between the emission
            // and the frame; viewModelScope would otherwise put it on Main.immediate.
            .flowOn(Dispatchers.Default)
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), LibraryView.Flat(emptyList()))

    fun onSortSelected(mode: BookSortMode) {
        viewModelScope.launch { repository.setSortMode(mode) }
    }

    /** Called with a tree URI the UI has already taken a persistable grant on. */
    fun onFolderPicked(treeUri: String) {
        viewModelScope.launch { repository.setFolderAndRescan(treeUri) }
    }

    fun onRescan() {
        viewModelScope.launch { repository.rescan() }
    }
}
