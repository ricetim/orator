package com.orator.feature.audiobooks

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.orator.core.database.BookEntity
import com.orator.core.database.BookmarkEntity
import com.orator.core.database.ChapterEntity
import com.orator.core.database.SourceKind
import com.orator.core.model.MediaType
import com.orator.core.playback.PlaybackConnection
import com.orator.core.playback.PlaybackUiState
import com.orator.core.playback.PlayerPreferences
import com.orator.core.playback.SmartRewind
import com.orator.feature.audiobooks.data.AudiobookMediaId
import com.orator.feature.audiobooks.data.AudiobookRepository
import com.orator.feature.audiobooks.data.PositionMapper
import com.orator.feature.audiobooks.data.QueueBuilder
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class BookDetailViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val repository: AudiobookRepository,
    private val playbackConnection: PlaybackConnection,
    private val playerPreferences: PlayerPreferences,
) : ViewModel() {

    private val bookId: String = checkNotNull(savedStateHandle["bookId"])

    val book: StateFlow<BookEntity?> = repository.observeBook(bookId)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    val chapters: StateFlow<List<ChapterEntity>> = repository.observeChapters(bookId)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val bookmarks: StateFlow<List<BookmarkEntity>> = repository.observeBookmarks(bookId)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val playback: StateFlow<PlaybackUiState> = playbackConnection.state

    /** True when whatever the service is playing belongs to THIS book. */
    fun isThisBook(state: PlaybackUiState): Boolean =
        state.mediaId?.let { AudiobookMediaId.parse(it)?.bookId } == bookId

    fun onPlayResume() {
        viewModelScope.launch {
            val b = repository.observeBook(bookId).first() ?: return@launch
            // Cold-start smart rewind: same tiers as the service's warm path, computed from
            // the last position ping. The service resets its warm state on queue load, so
            // the two can never stack.
            val prefs = playerPreferences.flow.first()
            val rewind = if (prefs.smartRewind[MediaType.AUDIOBOOK] == true && b.lastPlayedAtMs > 0) {
                SmartRewind.rewindMs(System.currentTimeMillis() - b.lastPlayedAtMs)
            } else {
                0
            }
            playFrom((b.positionMs - rewind).coerceAtLeast(0))
        }
    }

    fun onPlayPause() = playbackConnection.playPause()

    fun onChapterClick(chapter: ChapterEntity) {
        viewModelScope.launch {
            val b = repository.observeBook(bookId).first() ?: return@launch
            val all = repository.chaptersFor(bookId)
            val globalStart = when (b.sourceKind) {
                SourceKind.M4B -> chapter.startMs
                SourceKind.MP3_DIR -> PositionMapper.toGlobal(
                    all.map { it.durationMs },
                    chapter.chapterIndex,
                    0,
                )
            }
            playFrom(globalStart)
        }
    }

    fun onBookmarkClick(bookmark: BookmarkEntity) {
        viewModelScope.launch { playFrom(bookmark.positionMs) }
    }

    fun onAddBookmark() {
        viewModelScope.launch {
            val b = repository.observeBook(bookId).first() ?: return@launch
            val state = playback.value
            val global = if (isThisBook(state)) currentGlobalPosition(b, state) else b.positionMs
            repository.addBookmark(bookId, global)
        }
    }

    fun onDeleteBookmark(id: Long) {
        viewModelScope.launch { repository.deleteBookmark(id) }
    }

    private suspend fun currentGlobalPosition(book: BookEntity, state: PlaybackUiState): Long =
        when (book.sourceKind) {
            SourceKind.M4B -> state.positionMs
            SourceKind.MP3_DIR -> PositionMapper.toGlobal(
                repository.chaptersFor(bookId).map { it.durationMs },
                AudiobookMediaId.parse(state.mediaId.orEmpty())?.fileIndex ?: 0,
                state.positionMs,
            )
        }

    /** v1 keeps it simple: any jump rebuilds the queue and starts playing from globalMs. */
    private suspend fun playFrom(globalMs: Long) {
        val b = repository.observeBook(bookId).first() ?: return
        playbackConnection.play(QueueBuilder.build(b, repository.chaptersFor(bookId), globalMs))
    }
}
