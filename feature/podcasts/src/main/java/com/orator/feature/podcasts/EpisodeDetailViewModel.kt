package com.orator.feature.podcasts

import android.content.Context
import android.net.Uri
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.orator.core.database.EpisodeDao
import com.orator.core.database.EpisodeEntity
import com.orator.core.database.PodcastDao
import com.orator.core.database.PodcastEntity
import com.orator.core.model.MediaType
import com.orator.core.playback.PlaybackConnection
import com.orator.core.playback.PlaybackUiState
import com.orator.core.playback.PlayerPreferences
import com.orator.core.playback.SmartRewind
import com.orator.feature.podcasts.data.EpisodeDownloader
import com.orator.feature.podcasts.data.EpisodeQueueBuilder
import com.orator.core.playback.ids.PodcastMediaId
import com.orator.core.designsystem.text.ShowNotes
import com.orator.feature.podcasts.data.TranscriptFetcher
import com.orator.core.designsystem.text.TranscriptText
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.inject.Inject

@HiltViewModel
class EpisodeDetailViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    @ApplicationContext private val context: Context,
    private val episodeDao: EpisodeDao,
    private val podcastDao: PodcastDao,
    private val playbackConnection: PlaybackConnection,
    private val playerPreferences: PlayerPreferences,
    private val downloader: EpisodeDownloader,
    private val transcriptFetcher: TranscriptFetcher,
) : ViewModel() {

    private val episodeId: String = checkNotNull(savedStateHandle["episodeId"])

    val episode: StateFlow<EpisodeEntity?> = episodeDao.observeById(episodeId)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    val notes: StateFlow<ShowNotes.Rendered?> = episodeDao.observeById(episodeId)
        .map { e -> e?.showNotesHtml?.let(ShowNotes::render) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    val playback: StateFlow<PlaybackUiState> = playbackConnection.state

    val downloadProgress: StateFlow<Float?> = downloader.progress
        .map { it[episodeId] }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    /** "Download complete" / "Download failed: …" from the most recent attempt. */
    val downloadEvent: StateFlow<String?> = downloader.lastEvent

    /** Rendered transcript text once a file exists; null until fetched. */
    val transcript: StateFlow<String?> = episodeDao.observeById(episodeId)
        .map { e ->
            val path = e?.transcriptPath ?: return@map null
            withContext(Dispatchers.IO) {
                runCatching {
                    context.contentResolver.openInputStream(Uri.parse(path))
                        ?.use { it.readBytes().decodeToString() }
                }.getOrNull()?.let { TranscriptText.render(it, e.transcriptType) }
            }
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    val transcriptEvent: StateFlow<String?> = transcriptFetcher.lastEvent

    fun onGetTranscript() {
        viewModelScope.launch { transcriptFetcher.fetch(episodeId) }
    }

    fun isThisEpisode(state: PlaybackUiState): Boolean =
        state.mediaId?.let(PodcastMediaId::parse) == episodeId

    fun onPlayResume() {
        viewModelScope.launch {
            val e = episodeDao.getById(episodeId) ?: return@launch
            // Cold-start smart rewind, podcast flavor — same tiers as BookDetailViewModel;
            // warm/cold can't stack because the service resets on queue load (P3 invariant).
            val prefs = playerPreferences.flow.first()
            val rewind = if (prefs.smartRewind[MediaType.PODCAST] == true && e.lastPlayedAtMs > 0) {
                SmartRewind.rewindMs(System.currentTimeMillis() - e.lastPlayedAtMs)
            } else {
                0
            }
            playFrom((e.positionMs - rewind).coerceAtLeast(0))
        }
    }

    fun onPlayPause() = playbackConnection.playPause()

    /** [rawPositionMs] is original-timeline (show-note links); stored positions are clip-relative. */
    fun onTimestampTap(rawPositionMs: Long) {
        viewModelScope.launch {
            val podcast = podcastFor() ?: return@launch
            val clipRelative = (rawPositionMs - podcast.clipIntroMs).coerceAtLeast(0)
            if (isThisEpisode(playback.value)) {
                playbackConnection.seekWithinCurrent(clipRelative)
            } else {
                playFrom(clipRelative)
            }
        }
    }

    /** Fire-and-forget: the singleton downloader owns the job, so leaving this screen doesn't cancel it. */
    fun onDownload() = downloader.enqueue(episodeId)

    fun onCancelDownload() = downloader.cancel(episodeId)

    fun onDeleteDownload() {
        viewModelScope.launch { downloader.deleteDownload(episodeId) }
    }

    private suspend fun podcastFor(): PodcastEntity? =
        episodeDao.getById(episodeId)?.let { podcastDao.getById(it.podcastId) }

    private suspend fun playFrom(clipRelativeMs: Long) {
        val e = episodeDao.getById(episodeId) ?: return
        val podcast = podcastDao.getById(e.podcastId) ?: return
        playbackConnection.play(EpisodeQueueBuilder.build(podcast, e, clipRelativeMs))
    }
}
