package com.orator.feature.podcasts

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.orator.core.database.EpisodeEntity

@Composable
fun PodcastDetailScreen(
    onEpisodeClick: (String) -> Unit,
    viewModel: PodcastDetailViewModel = hiltViewModel(),
) {
    val podcast by viewModel.podcast.collectAsStateWithLifecycle()
    val episodes by viewModel.episodes.collectAsStateWithLifecycle()
    val p = podcast ?: return

    Column(
        modifier = Modifier.fillMaxSize().padding(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(p.title)
        Text(p.author ?: "")

        // Per-show settings: clip steppers (±15 s) and speed override (±0.1, Clear)
        SettingRow(
            "Skip intro: ${p.clipIntroMs / 1000}s",
            onMinus = { viewModel.onClipChange(p.clipIntroMs - 15_000, p.clipOutroMs) },
            onPlus = { viewModel.onClipChange(p.clipIntroMs + 15_000, p.clipOutroMs) },
        )
        SettingRow(
            "Skip outro: ${p.clipOutroMs / 1000}s",
            onMinus = { viewModel.onClipChange(p.clipIntroMs, p.clipOutroMs - 15_000) },
            onPlus = { viewModel.onClipChange(p.clipIntroMs, p.clipOutroMs + 15_000) },
        )
        SettingRow(
            "Speed: ${p.speedOverride?.let { "%.2f×".format(it) } ?: "default"}",
            onMinus = { viewModel.onSpeedOverride((p.speedOverride ?: 1.0f) - 0.1f) },
            onPlus = { viewModel.onSpeedOverride((p.speedOverride ?: 1.0f) + 0.1f) },
            extra = { OutlinedButton(onClick = { viewModel.onSpeedOverride(null) }) { Text("Clear") } },
        )

        LazyColumn(modifier = Modifier.weight(1f)) {
            items(episodes, key = EpisodeEntity::id) { episode ->
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { onEpisodeClick(episode.id) }
                        .padding(vertical = 12.dp),
                ) {
                    Text(episode.title)
                    Text(
                        listOfNotNull(
                            // Visible duration doubles as a clip diagnostic: outro skip only
                            // works on episodes whose duration is known.
                            episode.durationMs.takeIf { it > 0 }?.let(::formatDuration),
                            if (episode.audioPath != null) "downloaded" else null,
                            if (episode.positionMs > 0) "in progress" else null,
                        ).joinToString(" · ").ifEmpty { " " },
                    )
                }
            }
        }
    }
}

private fun formatDuration(ms: Long): String {
    val minutes = ms / 60_000
    return if (minutes >= 60) "${minutes / 60}h ${minutes % 60}m" else "${minutes}m"
}

@Composable
private fun SettingRow(
    label: String,
    onMinus: () -> Unit,
    onPlus: () -> Unit,
    extra: @Composable () -> Unit = {},
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, modifier = Modifier.weight(1f))
        OutlinedButton(onClick = onMinus) { Text("−") }
        OutlinedButton(onClick = onPlus) { Text("+") }
        extra()
    }
}
