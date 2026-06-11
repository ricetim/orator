package com.orator.feature.podcasts

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.ClickableText
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle

@Composable
fun EpisodeDetailScreen(viewModel: EpisodeDetailViewModel = hiltViewModel()) {
    val episode by viewModel.episode.collectAsStateWithLifecycle()
    val notes by viewModel.notes.collectAsStateWithLifecycle()
    val playback by viewModel.playback.collectAsStateWithLifecycle()
    val downloadProgress by viewModel.downloadProgress.collectAsStateWithLifecycle()
    val downloadEvent by viewModel.downloadEvent.collectAsStateWithLifecycle()
    val transcript by viewModel.transcript.collectAsStateWithLifecycle()
    val transcriptEvent by viewModel.transcriptEvent.collectAsStateWithLifecycle()
    val e = episode ?: return

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(e.title, style = MaterialTheme.typography.titleMedium)
        Text(if (e.audioPath != null) "Downloaded" else "Streams from feed")

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            val active = viewModel.isThisEpisode(playback)
            Button(onClick = {
                if (active) viewModel.onPlayPause() else viewModel.onPlayResume()
            }) {
                Text(if (active && playback.isPlaying) "Pause" else "Play")
            }
            when {
                downloadProgress != null -> OutlinedButton(onClick = viewModel::onCancelDownload) {
                    val pct = downloadProgress?.takeIf { it >= 0 }
                        ?.let { " ${(it * 100).toInt()}%" } ?: ""
                    Text("Cancel$pct")
                }
                e.audioPath != null -> OutlinedButton(onClick = viewModel::onDeleteDownload) {
                    Text("Delete download")
                }
                else -> OutlinedButton(onClick = viewModel::onDownload) { Text("Download") }
            }
        }
        downloadEvent?.let { Text(it) }
        transcriptEvent?.let { Text(it) }
        if (e.transcriptUrl != null && e.transcriptPath == null) {
            OutlinedButton(onClick = viewModel::onGetTranscript) { Text("Get transcript") }
        }

        notes?.let { rendered ->
            val annotated = buildAnnotatedString {
                append(rendered.text)
                rendered.links.forEachIndexed { index, link ->
                    addStyle(
                        SpanStyle(
                            color = MaterialTheme.colorScheme.primary,
                            textDecoration = TextDecoration.Underline,
                        ),
                        link.startIndex, link.endIndex,
                    )
                    addStringAnnotation("timestamp", "$index", link.startIndex, link.endIndex)
                }
            }
            // ClickableText is deprecated in newer Compose but fine under BOM 2024.12.01;
            // replaced during the UI phase along with the rest of the placeholder screens.
            ClickableText(
                text = annotated,
                style = MaterialTheme.typography.bodyMedium.copy(
                    color = MaterialTheme.colorScheme.onSurface,
                ),
            ) { offset ->
                annotated.getStringAnnotations("timestamp", offset, offset).firstOrNull()
                    ?.let { viewModel.onTimestampTap(rendered.links[it.item.toInt()].positionMs) }
            }
        }

        transcript?.let { text ->
            Text("Transcript", style = MaterialTheme.typography.titleMedium)
            Text(text, style = MaterialTheme.typography.bodyMedium)
        }
    }
}
