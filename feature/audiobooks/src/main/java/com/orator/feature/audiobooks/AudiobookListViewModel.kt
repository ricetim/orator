package com.orator.feature.audiobooks

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.orator.core.database.BookEntity
import com.orator.core.model.BookDownloadController
import com.orator.core.model.MediaType
import com.orator.core.playback.PlaybackConnection
import com.orator.core.playback.PlayerPreferences
import com.orator.core.playback.SmartRewind
import com.orator.core.playback.ids.AudiobookMediaId
import com.orator.feature.audiobooks.data.AudiobookRepository
import com.orator.feature.audiobooks.data.QueueBuilder
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class AudiobookListViewModel @Inject constructor(
    private val repository: AudiobookRepository,
    private val playbackConnection: PlaybackConnection,
    private val playerPreferences: PlayerPreferences,
    private val downloadControllers: Set<@JvmSuppressWildcards BookDownloadController>,
) : ViewModel() {

    val books: StateFlow<List<BookEntity>> = repository.observeBooks()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /** Offline download actions for non-local books, dispatched to the controller for their origin. */
    fun onDownload(book: BookEntity) {
        downloadControllers.firstOrNull { it.handles(book.origin) }?.enqueue(book.id)
    }

    fun onRemoveDownload(book: BookEntity) {
        val controller = downloadControllers.firstOrNull { it.handles(book.origin) } ?: return
        viewModelScope.launch { controller.remove(book.id) }
    }

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

    /**
     * Tile tap: resume (or start) the book, then the UI opens the player. Relocated from
     * BookDetailViewModel.onPlayResume — the player decorates loaded media, it never
     * cold-starts, so this is the only entry point for books.
     */
    fun onPlayBook(bookId: String, onStarted: () -> Unit) {
        viewModelScope.launch {
            val s = playbackConnection.state.value
            if (s.mediaId?.let { AudiobookMediaId.parse(it)?.bookId } == bookId) {
                if (!s.isPlaying) playbackConnection.playPause() // loaded but paused → resume
                onStarted() // then just open the player
                return@launch
            }
            val b = repository.observeBook(bookId).first() ?: return@launch
            // Cold-start smart rewind: same tiers as the service's warm path; the service
            // resets its warm state on queue load, so the two can never stack.
            val prefs = playerPreferences.flow.first()
            val rewind = if (prefs.smartRewind[MediaType.AUDIOBOOK] == true && b.lastPlayedAtMs > 0) {
                SmartRewind.rewindMs(System.currentTimeMillis() - b.lastPlayedAtMs)
            } else {
                0
            }
            playbackConnection.play(
                QueueBuilder.build(
                    b,
                    repository.chaptersFor(bookId),
                    (b.positionMs - rewind).coerceAtLeast(0),
                ),
            )
            onStarted()
        }
    }
}
