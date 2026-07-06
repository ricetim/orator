package com.orator.feature.audiobooks

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.orator.core.database.BookEntity
import com.orator.feature.audiobooks.data.AudiobookRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
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

    val books: StateFlow<List<BookEntity>> = repository.observeBooks()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val hasFolder: StateFlow<Boolean> = repository.treeUri
        .map { it != null }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), false)

    val sortMode: StateFlow<BookSortMode> = repository.sortMode
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), BookSortMode.RECENT)

    val view: StateFlow<LibraryView> =
        combine(repository.observeBooks(), repository.sortMode) { books, mode ->
            when (mode) {
                BookSortMode.RECENT, BookSortMode.TITLE -> LibraryView.Flat(BookExplore.sort(books, mode))
                BookSortMode.AUTHOR, BookSortMode.SERIES -> LibraryView.Sectioned(BookExplore.group(books, mode))
            }
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), LibraryView.Flat(emptyList()))

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
