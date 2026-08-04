package com.orator.app

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.orator.core.database.BookDao
import com.orator.core.database.EpisodeDao
import com.orator.core.database.PodcastDao
import com.orator.core.database.artworkModel
import com.orator.core.designsystem.text.TimeFormats
import com.orator.core.playback.PlaybackConnection
import com.orator.core.playback.PlayerPreferences
import com.orator.core.playback.PlayerPrefs
import com.orator.core.playback.ids.AudiobookMediaId
import com.orator.core.playback.ids.PodcastMediaId
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import javax.inject.Inject

/** Backs the app shell: mini-player state and the drawer's library counts. */
@HiltViewModel
class ShellViewModel @Inject constructor(
    private val playbackConnection: PlaybackConnection,
    playerPreferences: PlayerPreferences,
    private val bookDao: BookDao,
    private val episodeDao: EpisodeDao,
    private val podcastDao: PodcastDao,
) : ViewModel() {

    data class MiniPlayerUi(
        val visible: Boolean = false,
        val title: String = "",
        val subLine: String = "",
        val progress: Float = 0f,
        val isPlaying: Boolean = false,
        val artworkModel: Any? = null,
    )

    data class LibraryCounts(val podcasts: Int = 0, val books: Int = 0)

    /** Artwork for the loaded item: a book cover (File when local, URL when ABS) or a show
     *  artwork URL. */
    private val artwork = playbackConnection.state
        .map { it.mediaId }
        .distinctUntilChanged()
        .map { id -> resolveArtwork(id) }

    val mini: StateFlow<MiniPlayerUi> = combine(
        playbackConnection.state,
        playerPreferences.flow,
        artwork,
    ) { s, p: PlayerPrefs, art ->
        MiniPlayerUi(
            visible = s.mediaId != null,
            title = s.title,
            subLine = TimeFormats.miniSubLine(s.positionMs, s.durationMs, s.speed, p.silenceTrim),
            progress = if (s.durationMs > 0) s.positionMs.toFloat() / s.durationMs else 0f,
            isPlaying = s.isPlaying,
            artworkModel = art,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), MiniPlayerUi())

    val counts: StateFlow<LibraryCounts> = combine(
        podcastDao.observeAll(),
        bookDao.observeAll(),
    ) { pods, books -> LibraryCounts(pods.size, books.size) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), LibraryCounts())

    fun onPlayPause() = playbackConnection.playPause()

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
