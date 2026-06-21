package com.orator.feature.podcasts

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.orator.core.database.EpisodeEntity
import com.orator.core.database.PodcastEntity
import com.orator.core.designsystem.components.ArtworkImage
import com.orator.core.designsystem.components.DateBlock
import com.orator.core.designsystem.components.EffectsSheet
import com.orator.core.designsystem.components.EffectsSheetState
import com.orator.core.designsystem.components.EpisodeRow
import com.orator.core.designsystem.components.OnyxTopBar
import com.orator.core.designsystem.icons.OnyxIcons
import com.orator.core.designsystem.components.SwipeActionRow
import com.orator.core.designsystem.text.TimeFormats
import com.orator.core.designsystem.theme.OnyxTokens
import com.orator.core.model.MediaType
import com.orator.core.playback.PlayerPrefs
import java.time.Instant
import java.time.ZoneId
import java.time.format.TextStyle
import java.util.Locale

@Composable
fun PodcastDetailScreen(
    onOpenPlayer: () -> Unit,
    onBack: () -> Unit,
    onUnsubscribed: () -> Unit,
    onAddToPlaylist: (episodeId: String) -> Unit,
    viewModel: PodcastDetailViewModel = hiltViewModel(),
) {
    val podcast by viewModel.podcast.collectAsStateWithLifecycle()
    val episodes by viewModel.episodes.collectAsStateWithLifecycle()
    val progressMap by viewModel.downloadProgress.collectAsStateWithLifecycle()
    val prefs by viewModel.prefs.collectAsStateWithLifecycle()
    var showEffects by remember { mutableStateOf(false) }
    var confirmUnsubscribe by remember { mutableStateOf(false) }

    Column(Modifier.fillMaxSize().background(OnyxTokens.Background)) {
        OnyxTopBar(
            title = podcast?.title.orEmpty(),
            leadingIcon = Icons.AutoMirrored.Filled.ArrowBack,
            onLeadingClick = onBack,
        )
        LazyColumn(contentPadding = PaddingValues(bottom = OnyxTokens.OverlayBottomPadding)) {
            item {
                ShowHeader(
                    podcast = podcast,
                    episodeCount = episodes.size,
                    prefs = prefs,
                    onSubscribedClick = { confirmUnsubscribe = true },
                    onEffectsClick = { showEffects = true },
                )
            }
            items(episodes, key = { it.id }) { episode ->
                EpisodeListRow(
                    episode = episode,
                    progress = progressMap[episode.id],
                    onClick = { viewModel.onPlayEpisode(episode.id, onOpenPlayer) },
                    onDownload = { viewModel.onDownload(episode.id) },
                    onCancel = { viewModel.onCancelDownload(episode.id) },
                    onDeleteDownload = { viewModel.onDeleteDownload(episode.id) },
                    onAddToPlaylist = { onAddToPlaylist(episode.id) },
                )
            }
        }
    }

    val p = podcast
    if (confirmUnsubscribe && p != null) {
        AlertDialog(
            onDismissRequest = { confirmUnsubscribe = false },
            title = { Text("Unsubscribe?") },
            text = { Text("Unsubscribe from ${p.title}? Downloads are removed.") },
            confirmButton = {
                TextButton(onClick = {
                    confirmUnsubscribe = false
                    viewModel.onUnsubscribe(onUnsubscribed)
                }) { Text("Unsubscribe", color = OnyxTokens.SwipeDelete) }
            },
            dismissButton = {
                TextButton(onClick = { confirmUnsubscribe = false }) { Text("Cancel") }
            },
        )
    }

    if (showEffects && p != null) {
        EffectsSheet(
            state = EffectsSheetState(
                title = "Effects — ${p.title}",
                speed = p.speedOverride ?: prefs.perTypeSpeed[MediaType.PODCAST] ?: prefs.globalSpeed,
                overrideEnabled = p.speedOverride != null,
                trimOn = prefs.silenceTrim,
                boostMb = prefs.boostMb,
                clip = p.clipIntroMs to p.clipOutroMs,
            ),
            onDismiss = { showEffects = false },
            onSpeed = { v ->
                if (p.speedOverride != null) viewModel.onSpeedOverride(v) else viewModel.onTypeSpeed(v)
            },
            onOverrideToggle = { on ->
                if (on) {
                    viewModel.onSpeedOverride(
                        p.speedOverride ?: prefs.perTypeSpeed[MediaType.PODCAST] ?: prefs.globalSpeed,
                    )
                } else {
                    viewModel.onSpeedOverride(null) // OFF clears the override (null = fall back)
                }
            },
            onTrim = viewModel::onTrim,
            onBoost = viewModel::onBoost,
            onClip = { intro, outro -> viewModel.onClipChange(intro, outro) },
        )
    }
}

@Composable
private fun ShowHeader(
    podcast: PodcastEntity?,
    episodeCount: Int,
    prefs: PlayerPrefs,
    onSubscribedClick: () -> Unit,
    onEffectsClick: () -> Unit,
) {
    podcast ?: return
    Row(Modifier.padding(start = 16.dp, end = 16.dp, bottom = 10.dp, top = 4.dp)) {
        Box(Modifier.size(86.dp)) {
            ArtworkImage(
                model = podcast.artworkUrl,
                title = podcast.title,
                modifier = Modifier.size(86.dp),
                cornerRadius = 14.dp,
                initialsSize = 28.sp,
            )
        }
        Column(Modifier.padding(start = 14.dp)) {
            Text(
                podcast.title,
                color = OnyxTokens.Text,
                fontSize = 17.sp,
                fontWeight = FontWeight.ExtraBold,
                lineHeight = 21.sp,
            )
            Text(
                listOfNotNull(podcast.author, "$episodeCount episodes").joinToString(" · "),
                color = OnyxTokens.TextDim,
                fontSize = 12.5.sp,
                modifier = Modifier.padding(top = 3.dp, bottom = 8.dp),
            )
            Row {
                Pill(text = "✓ Subscribed", filled = true, onClick = onSubscribedClick)
                Pill(
                    text = effectsSummary(podcast, prefs),
                    filled = false,
                    onClick = onEffectsClick,
                    modifier = Modifier.padding(start = 5.dp),
                )
            }
        }
    }
}

private fun effectsSummary(podcast: PodcastEntity, prefs: PlayerPrefs): String {
    val speed = podcast.speedOverride ?: prefs.perTypeSpeed[MediaType.PODCAST] ?: prefs.globalSpeed
    val clips = if (podcast.clipIntroMs > 0 || podcast.clipOutroMs > 0) {
        " · −${podcast.clipIntroMs / 1000}s/−${podcast.clipOutroMs / 1000}s"
    } else {
        ""
    }
    return TimeFormats.speedLabel(speed) + clips
}

@Composable
private fun Pill(
    text: String,
    filled: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val shape = RoundedCornerShape(999.dp)
    Text(
        text,
        color = if (filled) OnyxTokens.OnAccent else OnyxTokens.TextDim,
        fontSize = 11.5.sp,
        fontWeight = FontWeight.Bold,
        modifier = modifier
            .clip(shape)
            .background(if (filled) OnyxTokens.Accent else OnyxTokens.ChipBackground)
            .border(1.dp, if (filled) OnyxTokens.Accent else OnyxTokens.ChipBorder, shape)
            .clickable(onClick = onClick)
            .padding(horizontal = 11.dp, vertical = 5.dp),
    )
}

@Composable
private fun EpisodeListRow(
    episode: EpisodeEntity,
    progress: Float?,
    onClick: () -> Unit,
    onDownload: () -> Unit,
    onCancel: () -> Unit,
    onDeleteDownload: () -> Unit,
    onAddToPlaylist: () -> Unit,
) {
    val downloaded = episode.audioPath != null
    SwipeActionRow(
        enabled = downloaded,
        actionLabel = "Delete ✕",
        onSwipeLeft = onDeleteDownload,
    ) {
        val date = Instant.ofEpochMilli(episode.pubDateUtc).atZone(ZoneId.systemDefault()).toLocalDate()
        EpisodeRow(
            title = episode.title,
            subLine = buildAnnotatedString {
                if (episode.durationMs > 0) append(TimeFormats.clock(episode.durationMs) + " · ")
                if (downloaded) {
                    withStyle(SpanStyle(color = OnyxTokens.Accent)) { append("↓ downloaded") }
                } else {
                    append("stream")
                }
                if (episode.transcriptPath != null) append(" · transcript")
            },
            onClick = onClick,
            leading = {
                DateBlock(
                    day = date.dayOfMonth.toString(),
                    month = date.month.getDisplayName(TextStyle.SHORT, Locale.US),
                )
            },
            trailing = {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    DownloadControl(
                        downloaded = downloaded,
                        progress = progress,
                        onDownload = onDownload,
                        onCancel = onCancel,
                    )
                    IconButton(onClick = onAddToPlaylist) {
                        Icon(OnyxIcons.Add, contentDescription = "Add to playlist", tint = OnyxTokens.TextDim)
                    }
                }
            },
        )
    }
}

@Composable
private fun DownloadControl(
    downloaded: Boolean,
    progress: Float?,
    onDownload: () -> Unit,
    onCancel: () -> Unit,
) {
    when {
        downloaded -> Unit // inline marker says it all
        progress != null -> Box(
            Modifier.size(40.dp).clickable(onClick = onCancel),
            contentAlignment = Alignment.Center,
        ) {
            if (progress < 0f) {
                CircularProgressIndicator(
                    Modifier.size(22.dp),
                    strokeWidth = 2.dp,
                    color = OnyxTokens.Accent,
                )
            } else {
                CircularProgressIndicator(
                    progress = { progress },
                    modifier = Modifier.size(22.dp),
                    strokeWidth = 2.dp,
                    color = OnyxTokens.Accent,
                    trackColor = OnyxTokens.BarTrack,
                )
            }
        }
        else -> IconButton(onClick = onDownload) {
            Text("↓", color = OnyxTokens.TextDim, fontSize = 18.sp)
        }
    }
}
