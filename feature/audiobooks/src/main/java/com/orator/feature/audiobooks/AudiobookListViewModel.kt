package com.orator.feature.audiobooks

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.orator.core.database.BookEntity
import com.orator.feature.audiobooks.data.AudiobookRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class AudiobookListViewModel @Inject constructor(
    private val repository: AudiobookRepository,
) : ViewModel() {

    val books: StateFlow<List<BookEntity>> = repository.observeBooks()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val hasFolder: StateFlow<Boolean> = repository.treeUri
        .map { it != null }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), false)

    /** Called with a tree URI the UI has already taken a persistable grant on. */
    fun onFolderPicked(treeUri: String) {
        viewModelScope.launch { repository.setFolderAndRescan(treeUri) }
    }

    fun onRescan() {
        viewModelScope.launch { repository.rescan() }
    }
}
