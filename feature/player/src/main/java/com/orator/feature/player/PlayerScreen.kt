package com.orator.feature.player

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.orator.core.database.SourceKind
import com.orator.core.designsystem.components.BarSpec
import com.orator.core.designsystem.components.DualProgressBars
import com.orator.core.designsystem.components.PagerDots
import com.orator.core.designsystem.icons.OnyxIcons
import com.orator.core.designsystem.text.TimeFormats
import com.orator.core.designsystem.theme.OnyxTokens
import com.orator.core.playback.PlaybackUiState
import com.orator.core.playback.SleepTimerState
import com.orator.feature.player.pages.BookmarksPage
import com.orator.feature.player.pages.ChaptersPage
import com.orator.feature.player.pages.CoverPage
import com.orator.feature.player.pages.NotesPage
import java.io.File
import kotlinx.coroutines.launch

/** The unified Onyx player: one layout for books and podcasts (mockup #pplayer/#bplayer). */
@Composable
fun PlayerScreen(
    onBack: () -> Unit,
    onOpenQueue: () -> Unit,
    viewModel: PlayerViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val content by viewModel.content.collectAsStateWithLifecycle()
    val sleep by viewModel.sleepState.collectAsStateWithLifecycle()
    val prefs by viewModel.prefs.collectAsStateWithLifecycle()
    var showSleep by remember { mutableStateOf(false) }
    var showEffects by remember { mutableStateOf(false) }

    val book = content as? NowPlayingContent.Book
    val isBook = book != null
    val tint = if (isBook) OnyxTokens.BookTint else OnyxTokens.PodcastTint
    val backdrop = Brush.verticalGradient(
        0f to tint,
        0.45f to OnyxTokens.Background,
        1f to OnyxTokens.Background,
    )

    val chapterUi = book?.let {
        PlayerChapters.current(
            it.chapters, it.book.sourceKind, state.currentIndex,
            state.positionMs, it.book.durationMs,
        )
    }

    Column(Modifier.fillMaxSize().background(backdrop)) {
        PlayerTopBar(contextLine = contextLine(content, state), onBack = onBack)
        PlayerPager(content, state, chapterUi, viewModel)
        PlayerMeta(content, chapterUi)
        PlayerBars(content, state, chapterUi, viewModel)
        PlayerTransport(
            isBook = isBook,
            hasChapters = book?.chapters?.isNotEmpty() == true,
            isPlaying = state.isPlaying,
            onPrev = {
                book?.let { b ->
                    PlayerChapters.previous(
                        b.chapters, b.book.sourceKind, state.currentIndex, state.positionMs,
                    )?.let(viewModel::onSeekTarget)
                }
            },
            onNext = {
                book?.let { b ->
                    PlayerChapters.next(
                        b.chapters, b.book.sourceKind, state.currentIndex, state.positionMs,
                    )?.let(viewModel::onSeekTarget)
                }
            },
            onBack15 = { viewModel.onSeekBy(-15_000) },
            onFwd30 = { viewModel.onSeekBy(30_000) },
            onPlayPause = viewModel::onPlayPauseClick,
        )
        Spacer(Modifier.weight(1f))
        PlayerBottomRow(
            sleepArmed = sleep != SleepTimerState.Off,
            onSleep = { showSleep = true },
            onEffects = { showEffects = true },
            onQueue = onOpenQueue,
        )
    }

    if (showSleep) {
        SleepSheet(
            sleep = sleep,
            isBook = isBook,
            onDuration = viewModel::onSleepDuration,
            onBoundary = viewModel::onSleepBoundary,
            onCancel = viewModel::onSleepCancel,
            onDismiss = { showSleep = false },
        )
    }
    if (showEffects) {
        PlayerEffectsHost(
            content = content,
            state = state,
            prefs = prefs,
            viewModel = viewModel,
            onDismiss = { showEffects = false },
        )
    }
}

private fun contextLine(content: NowPlayingContent, state: PlaybackUiState): String =
    when (content) {
        is NowPlayingContent.Book ->
            listOfNotNull(content.book.title, content.book.author).joinToString(" · ")
        is NowPlayingContent.Episode -> content.podcast?.title ?: state.artist
        NowPlayingContent.Empty -> ""
    }.uppercase()

@Composable
private fun PlayerTopBar(contextLine: String, onBack: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconButton(onClick = onBack) {
            Text("⌄", color = OnyxTokens.TextDim, fontSize = 19.sp)
        }
        Text(
            contextLine,
            color = OnyxTokens.TextDim,
            fontSize = 12.5.sp,
            fontWeight = FontWeight.Bold,
            letterSpacing = 1.sp,
            textAlign = TextAlign.Center,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        Spacer(Modifier.width(48.dp)) // balances the back button; no share target yet
    }
}

@Composable
private fun PlayerPager(
    content: NowPlayingContent,
    state: PlaybackUiState,
    chapterUi: PlayerChapters.ChapterUi?,
    viewModel: PlayerViewModel,
) {
    val pageCount = when (content) {
        is NowPlayingContent.Book -> if (content.chapters.isEmpty()) 2 else 3
        is NowPlayingContent.Episode -> 2 // no podcast chapter data yet (spec)
        NowPlayingContent.Empty -> 1
    }
    val pagerState = rememberPagerState(pageCount = { pageCount })
    val scope = rememberCoroutineScope()

    Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.fillMaxWidth()) {
        HorizontalPager(state = pagerState, modifier = Modifier.fillMaxWidth().height(348.dp)) { page ->
            when (content) {
                is NowPlayingContent.Book -> when (page) {
                    0 -> CoverPage(content.book.coverPath?.let(::File), content.book.title)
                    1 -> BookmarksPage(
                        bookmarks = content.bookmarks,
                        chapters = content.chapters,
                        sourceKind = content.book.sourceKind,
                        currentGlobalMs = viewModel.currentGlobalMs(content),
                        onBookmarkTap = viewModel::onBookmarkTap,
                        onDeleteBookmark = viewModel::onDeleteBookmark,
                        onAddBookmark = viewModel::onAddBookmark,
                    )
                    else -> ChaptersPage(
                        chapters = content.chapters,
                        sourceKind = content.book.sourceKind,
                        currentChapterIndex = chapterUi?.index,
                        onChapterTap = { i ->
                            viewModel.onSeekTarget(
                                PlayerChapters.tap(content.chapters, content.book.sourceKind, i),
                            )
                        },
                    )
                }
                is NowPlayingContent.Episode -> when (page) {
                    0 -> CoverPage(content.podcast?.artworkUrl, content.episode.title)
                    else -> NotesPage(
                        notes = content.notes,
                        transcript = content.transcript,
                        transcriptAvailableButNotFetched =
                            content.episode.transcriptUrl != null && content.transcript == null,
                        onTimestampTap = viewModel::onTimestampTap,
                    )
                }
                NowPlayingContent.Empty -> Box(Modifier.fillMaxSize(), Alignment.Center) {
                    Text("Nothing playing", color = OnyxTokens.TextFaint)
                }
            }
        }
        PagerDots(
            count = pageCount,
            selected = pagerState.currentPage,
            onSelect = { scope.launch { pagerState.animateScrollToPage(it) } },
        )
    }
}

@Composable
private fun PlayerMeta(content: NowPlayingContent, chapterUi: PlayerChapters.ChapterUi?) {
    val (title, sub) = when (content) {
        is NowPlayingContent.Book ->
            (chapterUi?.title ?: content.book.title) to content.book.author.orEmpty()
        // Mockup shows a chapter line here, but its fictional podcast has chapters; we have
        // no podcast chapter data, so the show name takes the dim line (spec deviation note).
        is NowPlayingContent.Episode ->
            content.episode.title to (content.podcast?.title ?: "")
        NowPlayingContent.Empty -> "Nothing playing" to ""
    }
    Column(
        Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 6.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            title,
            color = OnyxTokens.Text,
            fontSize = 15.5.sp,
            fontWeight = FontWeight.Bold,
            textAlign = TextAlign.Center,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            lineHeight = 20.sp,
        )
        if (sub.isNotEmpty()) {
            Text(sub, color = OnyxTokens.TextDim, fontSize = 12.sp, maxLines = 1)
        }
    }
}

private fun safeDiv(a: Long, b: Long): Float = if (b <= 0) 0f else (a.toFloat() / b).coerceIn(0f, 1f)

@Composable
private fun PlayerBars(
    content: NowPlayingContent,
    state: PlaybackUiState,
    chapterUi: PlayerChapters.ChapterUi?,
    viewModel: PlayerViewModel,
) {
    when (content) {
        is NowPlayingContent.Book -> {
            val kind = content.book.sourceKind
            val itemFraction = PlayerChapters.itemFraction(
                content.chapters, kind, state.currentIndex, state.positionMs, content.book.durationMs,
            )
            val globalMs = viewModel.currentGlobalMs(content)
            DualProgressBars(
                chapter = chapterUi?.let {
                    BarSpec(
                        leftLabel = "Chapter ${it.index + 1} / ${it.count}",
                        rightLabel = TimeFormats.clock(it.positionInChapterMs) + " · −" +
                            TimeFormats.clock(
                                (it.chapterDurationMs - it.positionInChapterMs).coerceAtLeast(0),
                            ),
                        fraction = safeDiv(it.positionInChapterMs, it.chapterDurationMs),
                    )
                },
                item = BarSpec(
                    leftLabel = "Book",
                    rightLabel = "${(itemFraction * 100).toInt()}% · " +
                        TimeFormats.timeLeft((content.book.durationMs - globalMs).coerceAtLeast(0)),
                    fraction = itemFraction,
                    ticks = PlayerChapters.ticks(content.chapters, kind, content.book.durationMs),
                ),
                onChapterSeek = { f ->
                    chapterUi?.let { cu ->
                        val base = PlayerChapters.tap(content.chapters, kind, cu.index)
                        viewModel.onSeekTarget(
                            PlayerChapters.SeekTarget(
                                base.index,
                                base.positionMs + (f * cu.chapterDurationMs).toLong(),
                            ),
                        )
                    }
                },
                onItemSeek = { f ->
                    viewModel.onSeekTarget(
                        PlayerChapters.itemSeek(content.chapters, kind, f, content.book.durationMs),
                    )
                },
            )
        }
        else -> DualProgressBars(
            chapter = null,
            item = BarSpec(
                leftLabel = "Episode",
                rightLabel = TimeFormats.clock(state.positionMs) + " · " +
                    TimeFormats.remaining(state.positionMs, state.durationMs),
                fraction = safeDiv(state.positionMs, state.durationMs),
            ),
            onChapterSeek = {},
            onItemSeek = { f -> viewModel.onSeekWithin((f * state.durationMs).toLong()) },
        )
    }
}

@Composable
private fun PlayerTransport(
    isBook: Boolean,
    hasChapters: Boolean,
    isPlaying: Boolean,
    onPrev: () -> Unit,
    onNext: () -> Unit,
    onBack15: () -> Unit,
    onFwd30: () -> Unit,
    onPlayPause: () -> Unit,
) {
    Row(
        Modifier.fillMaxWidth().padding(top = 10.dp),
        horizontalArrangement = Arrangement.spacedBy(
            if (isBook) 20.dp else 34.dp,
            Alignment.CenterHorizontally,
        ),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (isBook && hasChapters) {
            IconButton(onClick = onPrev) {
                Icon(OnyxIcons.SkipPrevious, "Previous chapter", tint = OnyxTokens.TextDim,
                    modifier = Modifier.size(28.dp))
            }
        }
        SkipButton(glyph = "↺", label = "15", onClick = onBack15)
        Box(
            Modifier.size(68.dp).clip(CircleShape).background(OnyxTokens.Accent)
                .clickable(onClick = onPlayPause),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                if (isPlaying) OnyxIcons.Pause else Icons.Filled.PlayArrow,
                contentDescription = if (isPlaying) "Pause" else "Play",
                tint = OnyxTokens.OnAccent,
                modifier = Modifier.size(32.dp),
            )
        }
        SkipButton(glyph = "↻", label = "30", onClick = onFwd30)
        if (isBook && hasChapters) {
            IconButton(onClick = onNext) {
                Icon(OnyxIcons.SkipNext, "Next chapter", tint = OnyxTokens.TextDim,
                    modifier = Modifier.size(28.dp))
            }
        }
    }
}

@Composable
private fun SkipButton(glyph: String, label: String, onClick: () -> Unit) {
    Column(
        Modifier.clickable(onClick = onClick).padding(8.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(glyph, color = OnyxTokens.Text, fontSize = 23.sp)
        Text(label, color = OnyxTokens.TextDim, fontSize = 12.sp, fontWeight = FontWeight.ExtraBold)
    }
}

@Composable
private fun PlayerBottomRow(
    sleepArmed: Boolean,
    onSleep: () -> Unit,
    onEffects: () -> Unit,
    onQueue: () -> Unit,
) {
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 14.dp),
        horizontalArrangement = Arrangement.SpaceAround,
    ) {
        BottomAction("😴", "Sleep", highlight = sleepArmed, onClick = onSleep)
        BottomAction("⚡", "Effects", highlight = false, onClick = onEffects)
        BottomAction("⌸", "Queue", highlight = false, onClick = onQueue)
    }
}

@Composable
private fun BottomAction(glyph: String, label: String, highlight: Boolean, onClick: () -> Unit) {
    Column(
        Modifier.clickable(onClick = onClick).padding(8.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(glyph, fontSize = 19.sp)
        Text(
            label,
            color = if (highlight) OnyxTokens.AccentBright else OnyxTokens.TextDim,
            fontSize = 11.sp,
        )
    }
}
