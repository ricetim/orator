package com.orator.feature.player

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.orator.core.database.BookDao
import com.orator.core.database.ChapterDao
import com.orator.core.database.EpisodeDao
import com.orator.core.database.PodcastDao
import com.orator.core.database.artworkModel
import com.orator.core.playback.PlaybackConnection
import com.orator.core.playback.ids.AudiobookMediaId
import com.orator.core.playback.ids.PodcastMediaId
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
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
    private val bookDao: BookDao,
    private val episodeDao: EpisodeDao,
    private val podcastDao: PodcastDao,
) : ViewModel() {

    data class QueueUi(
        val rows: List<QueueRows.Row> = emptyList(),
        val loaded: Boolean = false,
        /** One artwork per queue context for now — today's queue is always one media item. */
        val artworkModel: Any? = null,
    )

    private val rows = playbackConnection.state
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

    /** Book cover File or show artwork URL for the loaded item (same lookup as the mini player). */
    private val artwork = playbackConnection.state
        .map { it.mediaId }
        .distinctUntilChanged()
        .map { id -> resolveArtwork(id) }

    val ui: StateFlow<QueueUi> = combine(rows, artwork) { base, art ->
        base.copy(artworkModel = art)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), QueueUi())

    fun onJump(target: PlayerChapters.SeekTarget) =
        playbackConnection.seekTo(target.index, target.positionMs)

    private suspend fun resolveArtwork(mediaId: String?): Any? {
        mediaId ?: return null
        AudiobookMediaId.parse(mediaId)?.let { parsed ->
            return bookDao.getById(parsed.bookId)?.artworkModel
        }
        PodcastMediaId.parse(mediaId)?.let { episodeId ->
            val episode = episodeDao.getById(episodeId) ?: return null
            return podcastDao.getById(episode.podcastId)?.artworkUrl
        }
        return null
    }
}
