package com.orator.feature.audiobooks

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.orator.core.database.BookEntity
import com.orator.core.designsystem.components.ArtworkImage
import com.orator.core.designsystem.components.OnyxTopBar
import com.orator.core.designsystem.text.TimeFormats
import com.orator.core.designsystem.theme.OnyxTokens
import com.orator.core.model.BookOrigin
import java.io.File
import java.time.Instant
import java.time.ZoneId
import java.time.format.TextStyle
import java.util.Locale

@Composable
fun AudiobookDetailScreen(
    onOpenPlayer: () -> Unit,
    onBack: () -> Unit,
    viewModel: AudiobookDetailViewModel = hiltViewModel(),
) {
    val book by viewModel.book.collectAsStateWithLifecycle()
    val resolving by viewModel.resolving.collectAsStateWithLifecycle()
    val b = book

    Column(Modifier.fillMaxSize().background(OnyxTokens.Background)) {
        OnyxTopBar(
            title = b?.title.orEmpty(),
            leadingIcon = Icons.AutoMirrored.Filled.ArrowBack,
            onLeadingClick = onBack,
        )
        if (b == null) return@Column
        val actions = bookActions(b.origin, b.downloadState)

        LazyColumn(
            contentPadding = PaddingValues(20.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            item {
                ArtworkImage(
                    model = artworkModel(b),
                    title = b.title,
                    modifier = Modifier.size(180.dp),
                    cornerRadius = 16.dp,
                    initialsSize = 44.sp,
                )
            }
            item {
                Text(
                    b.title, color = OnyxTokens.Text, fontSize = 20.sp, fontWeight = FontWeight.ExtraBold,
                    textAlign = TextAlign.Center, modifier = Modifier.padding(top = 14.dp),
                )
                b.author?.let {
                    Text(it, color = OnyxTokens.TextDim, fontSize = 14.sp, modifier = Modifier.padding(top = 2.dp))
                }
            }
            item {
                Column(
                    Modifier.fillMaxWidth().padding(top = 6.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    b.series?.let {
                        Text("Series · $it", color = OnyxTokens.TextDim, fontSize = 13.sp)
                    }
                    Text(TimeFormats.clock(b.durationMs), color = OnyxTokens.TextFaint, fontSize = 13.sp,
                        modifier = Modifier.padding(top = 2.dp))
                    Text(progressLine(b), color = OnyxTokens.TextFaint, fontSize = 12.5.sp,
                        modifier = Modifier.padding(top = 2.dp))
                }
            }
            item {
                Row(
                    Modifier.fillMaxWidth().padding(vertical = 16.dp),
                    horizontalArrangement = Arrangement.spacedBy(10.dp, Alignment.CenterHorizontally),
                ) {
                    ActionButton(actions.primary, b, primary = true, viewModel, onOpenPlayer)
                    actions.secondary?.let { ActionButton(it, b, primary = false, viewModel, onOpenPlayer) }
                }
            }
            item {
                val synopsis = b.description
                when {
                    synopsis != null -> Text(
                        synopsis, color = OnyxTokens.TextDim, fontSize = 14.sp, lineHeight = 20.sp,
                    )
                    resolving -> CircularProgressIndicator(
                        Modifier.size(22.dp), strokeWidth = 2.dp, color = OnyxTokens.Accent,
                    )
                }
            }
        }
    }
}

/** ABS covers are remote URLs (Coil loads them directly); local covers are file paths. */
private fun artworkModel(b: BookEntity): Any? =
    if (b.origin == BookOrigin.ABS) b.coverPath else b.coverPath?.let(::File)

private fun progressLine(b: BookEntity): String {
    if (b.positionMs <= 0 || b.durationMs <= 0) return "Not started"
    val pct = (b.positionMs * 100 / b.durationMs)
    val left = TimeFormats.timeLeft((b.durationMs - b.positionMs).coerceAtLeast(0))
    val last = if (b.lastPlayedAtMs > 0) " · last played " + dateLabel(b.lastPlayedAtMs) else ""
    return "$pct% · $left left$last"
}

private fun dateLabel(ms: Long): String {
    val d = Instant.ofEpochMilli(ms).atZone(ZoneId.systemDefault()).toLocalDate()
    return d.month.getDisplayName(TextStyle.SHORT, Locale.US) + " " + d.dayOfMonth
}

@Composable
private fun ActionButton(
    action: BookAction,
    book: BookEntity,
    primary: Boolean,
    viewModel: AudiobookDetailViewModel,
    onOpenPlayer: () -> Unit,
) {
    val label = when (action) {
        BookAction.PLAY_RESUME -> if (book.positionMs > 0) "Resume" else "Play"
        BookAction.STREAM -> "Stream"
        BookAction.DOWNLOAD -> "Download"
        BookAction.CANCEL_DOWNLOAD -> "Cancel"
        BookAction.REMOVE_DOWNLOAD -> "Remove download"
    }
    val onClick: () -> Unit = when (action) {
        BookAction.PLAY_RESUME, BookAction.STREAM -> { { viewModel.onPlay(onOpenPlayer) } }
        BookAction.DOWNLOAD -> viewModel::onDownload
        BookAction.CANCEL_DOWNLOAD -> viewModel::onCancelDownload
        BookAction.REMOVE_DOWNLOAD -> viewModel::onRemoveDownload
    }
    if (primary) {
        Button(
            onClick = onClick,
            colors = ButtonDefaults.buttonColors(
                containerColor = OnyxTokens.Accent, contentColor = OnyxTokens.OnAccent,
            ),
        ) { Text(label) }
    } else {
        OutlinedButton(
            onClick = onClick,
            colors = ButtonDefaults.outlinedButtonColors(contentColor = OnyxTokens.TextDim),
        ) { Text(label) }
    }
}
