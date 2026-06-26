package com.orator.feature.audiobooks

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.orator.core.database.BookEntity
import com.orator.core.database.ChapterEntity
import com.orator.core.model.BookDetailResolver
import com.orator.core.model.BookDownloadController
import com.orator.core.playback.PlaybackConnection
import com.orator.core.playback.PlayerPreferences
import com.orator.core.playback.ids.AudiobookMediaId
import com.orator.feature.audiobooks.data.AudiobookPlayPreparer
import com.orator.feature.audiobooks.data.AudiobookRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class AudiobookDetailViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val repository: AudiobookRepository,
    private val preparer: AudiobookPlayPreparer,
    private val detailResolvers: Set<@JvmSuppressWildcards BookDetailResolver>,
    private val downloadControllers: Set<@JvmSuppressWildcards BookDownloadController>,
    private val playbackConnection: PlaybackConnection,
    private val playerPreferences: PlayerPreferences,
) : ViewModel() {

    // Navigation-Compose decodes path args already; the route is built with Uri.encode (see
    // AudiobooksFeatureEntry), so read it raw here.
    private val bookId: String = checkNotNull(savedStateHandle["bookId"])

    val book: StateFlow<BookEntity?> = repository.observeBook(bookId)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)
    val chapters: StateFlow<List<ChapterEntity>> = repository.observeChapters(bookId)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    private val _resolving = MutableStateFlow(true)
    val resolving: StateFlow<Boolean> = _resolving.asStateFlow()

    init {
        viewModelScope.launch {
            val b = repository.observeBook(bookId).first()
            if (b != null) detailResolvers.firstOrNull { it.handles(b.origin) }?.ensureDetails(bookId)
            _resolving.value = false
        }
    }

    /** Start (or resume) playback, then open the player. */
    fun onPlay(onOpenPlayer: () -> Unit) {
        viewModelScope.launch {
            val s = playbackConnection.state.value
            if (s.mediaId?.let { AudiobookMediaId.parse(it)?.bookId } == bookId) {
                if (!s.isPlaying) playbackConnection.playPause()
                onOpenPlayer(); return@launch
            }
            val req = preparer.prepare(bookId, playerPreferences.flow.first()) ?: return@launch
            playbackConnection.play(req)
            onOpenPlayer()
        }
    }

    fun onDownload() { book.value?.let { b -> controllerFor(b)?.enqueue(b.id) } }
    fun onCancelDownload() { book.value?.let { b -> controllerFor(b)?.cancel(b.id) } }
    fun onRemoveDownload() {
        val b = book.value ?: return
        val c = controllerFor(b) ?: return
        viewModelScope.launch { c.remove(b.id) }
    }

    private fun controllerFor(b: BookEntity) = downloadControllers.firstOrNull { it.handles(b.origin) }
}
