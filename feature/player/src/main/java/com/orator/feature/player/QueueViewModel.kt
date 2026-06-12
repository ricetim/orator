package com.orator.feature.player

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.orator.core.database.ChapterDao
import com.orator.core.playback.PlaybackConnection
import com.orator.core.playback.ids.AudiobookMediaId
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import javax.inject.Inject

/** Read-only view of the live playback queue (full mixed queue arrives with Phase 5). */
@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class QueueViewModel @Inject constructor(
    private val playbackConnection: PlaybackConnection,
    chapterDao: ChapterDao,
) : ViewModel() {

    data class QueueUi(
        val rows: List<QueueRows.Row> = emptyList(),
        val loaded: Boolean = false,
    )

    val ui: StateFlow<QueueUi> = playbackConnection.state
        .flatMapLatest { s ->
            val bookId = s.mediaId?.let { AudiobookMediaId.parse(it)?.bookId }
            val chaptersFlow = if (bookId != null) {
                chapterDao.observeForBook(bookId)
            } else {
                flowOf(emptyList())
            }
            chaptersFlow.map { chapters ->
                QueueUi(
                    rows = QueueRows.build(
                        s.currentIndex, s.positionMs,
                        playbackConnection.queueSnapshot(), chapters,
                    ),
                    loaded = s.mediaId != null,
                )
            }
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), QueueUi())

    fun onJump(target: PlayerChapters.SeekTarget) =
        playbackConnection.seekTo(target.index, target.positionMs)
}
