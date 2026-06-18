package com.orator.feature.player

import android.content.Context
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.orator.core.database.BookDao
import com.orator.core.database.BookmarkDao
import com.orator.core.database.BookmarkEntity
import com.orator.core.database.ChapterDao
import com.orator.core.database.EpisodeDao
import com.orator.core.database.PodcastDao
import com.orator.core.database.SourceKind
import com.orator.core.designsystem.text.ShowNotes
import com.orator.core.designsystem.text.TranscriptText
import com.orator.core.playback.PlaybackConnection
import com.orator.core.playback.PlaybackUiState
import com.orator.core.playback.PlayerPreferences
import com.orator.core.playback.PlayerPrefs
import com.orator.core.playback.SleepTimer
import com.orator.core.playback.SleepTimerState
import com.orator.core.playback.ids.AudiobookMediaId
import com.orator.core.playback.ids.PodcastMediaId
import com.orator.core.playback.ids.PositionMapper
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.inject.Inject

/**
 * The unified player's state holder. Resolves the loaded mediaId into [NowPlayingContent]
 * (book + chapters + bookmarks, or episode + notes + transcript) and exposes every player
 * action. The player only decorates loaded media — it never cold-starts an item, so all
 * seeks are plain controller calls (see the Onyx plan's orientation).
 */
@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class PlayerViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val playbackConnection: PlaybackConnection,
    private val sleepTimer: SleepTimer,
    private val playerPreferences: PlayerPreferences,
    private val bookDao: BookDao,
    private val chapterDao: ChapterDao,
    private val bookmarkDao: BookmarkDao,
    private val episodeDao: EpisodeDao,
    private val podcastDao: PodcastDao,
) : ViewModel() {

    val uiState: StateFlow<PlaybackUiState> = playbackConnection.state
    val sleepState: StateFlow<SleepTimerState> = sleepTimer.state
    val prefs: StateFlow<PlayerPrefs> = playerPreferences.flow
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), PlayerPrefs())

    /** Stable per-item key: the bookId for books (mediaId changes per MP3 file). */
    private fun contentKey(mediaId: String?): String? = when {
        mediaId == null -> null
        else -> AudiobookMediaId.parse(mediaId)?.bookId ?: mediaId
    }

    val content: StateFlow<NowPlayingContent> = playbackConnection.state
        .map { contentKey(it.mediaId) to it.mediaId }
        .distinctUntilChanged { a, b -> a.first == b.first }
        .flatMapLatest { (_, mediaId) -> contentFlow(mediaId) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), NowPlayingContent.Empty)

    private fun contentFlow(mediaId: String?): Flow<NowPlayingContent> {
        mediaId ?: return flowOf(NowPlayingContent.Empty)
        AudiobookMediaId.parse(mediaId)?.let { parsed ->
            return combine(
                bookDao.observeById(parsed.bookId),
                chapterDao.observeForBook(parsed.bookId),
                bookmarkDao.observeForBook(parsed.bookId),
            ) { book, chapters, bookmarks ->
                if (book == null) {
                    NowPlayingContent.Empty
                } else {
                    NowPlayingContent.Book(book, chapters, bookmarks)
                }
            }
        }
        PodcastMediaId.parse(mediaId)?.let { episodeId ->
            return episodeDao.observeById(episodeId).map { e ->
                if (e == null) return@map NowPlayingContent.Empty
                NowPlayingContent.Episode(
                    episode = e,
                    podcast = podcastDao.getById(e.podcastId),
                    notes = e.showNotesHtml?.let(ShowNotes::render),
                    transcript = readTranscript(e.transcriptPath, e.transcriptType),
                )
            }
        }
        return flowOf(NowPlayingContent.Empty)
    }

    private suspend fun readTranscript(path: String?, type: String?): String? {
        path ?: return null
        return withContext(Dispatchers.IO) {
            runCatching {
                context.contentResolver.openInputStream(Uri.parse(path))
                    ?.use { it.readBytes().decodeToString() }
            }.getOrNull()?.let { TranscriptText.render(it, type) }
        }
    }

    // ── transport ────────────────────────────────────────────────────────────
    fun onPlayPauseClick() = playbackConnection.playPause()

    fun onSeekBy(deltaMs: Long) = playbackConnection.seekBy(deltaMs)

    fun onSeekTarget(target: PlayerChapters.SeekTarget) =
        playbackConnection.seekTo(target.index, target.positionMs)

    fun onSeekWithin(positionMs: Long) = playbackConnection.seekWithinCurrent(positionMs)

    // ── bookmarks (books) ────────────────────────────────────────────────────
    fun onAddBookmark() {
        val c = content.value as? NowPlayingContent.Book ?: return
        viewModelScope.launch {
            // note has NO default on the entity — null means "no note" (matches
            // AudiobookRepository.addBookmark).
            bookmarkDao.insert(
                BookmarkEntity(
                    bookId = c.book.id,
                    positionMs = currentGlobalMs(c),
                    note = null,
                    createdAtUtc = System.currentTimeMillis(),
                ),
            )
        }
    }

    fun onBookmarkTap(bookmark: BookmarkEntity) {
        val c = content.value as? NowPlayingContent.Book ?: return
        when (c.book.sourceKind) {
            SourceKind.M4B -> playbackConnection.seekTo(0, bookmark.positionMs)
            SourceKind.MP3_DIR -> {
                val p = PositionMapper.toFilePosition(
                    c.chapters.map { it.durationMs },
                    bookmark.positionMs,
                )
                playbackConnection.seekTo(p.fileIndex, p.offsetMs)
            }
        }
    }

    fun onDeleteBookmark(id: Long) {
        viewModelScope.launch { bookmarkDao.delete(id) }
    }

    /** Global book position of the playhead (M4B: as reported; MP3_DIR: via PositionMapper). */
    fun currentGlobalMs(c: NowPlayingContent.Book): Long {
        val s = uiState.value
        return when (c.book.sourceKind) {
            SourceKind.M4B -> s.positionMs
            SourceKind.MP3_DIR -> PositionMapper.toGlobal(
                c.chapters.map { it.durationMs },
                AudiobookMediaId.parse(s.mediaId.orEmpty())?.fileIndex ?: 0,
                s.positionMs,
            )
        }
    }

    // ── show-note timestamps (podcasts; raw = original timeline) ────────────
    fun onTimestampTap(rawPositionMs: Long) {
        val c = content.value as? NowPlayingContent.Episode ?: return
        val clipIntro = c.podcast?.clipIntroMs ?: 0
        playbackConnection.seekWithinCurrent((rawPositionMs - clipIntro).coerceAtLeast(0))
    }

    // ── sleep ────────────────────────────────────────────────────────────────
    fun onSleepDuration(minutes: Int) = sleepTimer.armDuration(minutes)
    fun onSleepBoundary() = sleepTimer.armBoundary()
    fun onSleepCancel() = sleepTimer.cancel()
    fun onDefaultSleep() {
        viewModelScope.launch {
            sleepTimer.armDuration(playerPreferences.flow.first().defaultSleepMinutes)
        }
    }

    // ── effects (sheet semantics per spec) ──────────────────────────────────
    fun isOverrideActive(): Boolean = when (val c = content.value) {
        is NowPlayingContent.Book -> c.book.speedOverride != null
        is NowPlayingContent.Episode -> c.podcast?.speedOverride != null
        NowPlayingContent.Empty -> false
    }

    fun onSpeed(speed: Float) {
        val rounded = (speed.coerceIn(0.5f, 3.0f) * 100).toInt() / 100f
        if (isOverrideActive()) {
            playbackConnection.setSpeedOverride(rounded)
        } else {
            val type = uiState.value.mediaType ?: return
            viewModelScope.launch { playerPreferences.setTypeSpeed(type, rounded) }
        }
    }

    fun onOverrideToggle(on: Boolean) =
        playbackConnection.setSpeedOverride(if (on) uiState.value.speed else null)

    fun onTrim(on: Boolean) {
        viewModelScope.launch { playerPreferences.setSilenceTrim(on) }
    }

    fun onBoost(mb: Int) {
        viewModelScope.launch { playerPreferences.setBoostMb(mb.coerceIn(0, 1500)) }
    }

    fun onClip(introMs: Long, outroMs: Long) = playbackConnection.setClipOverride(introMs, outroMs)
}
