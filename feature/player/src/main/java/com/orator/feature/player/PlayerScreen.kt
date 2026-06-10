package com.orator.feature.player

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.orator.core.playback.SleepTimerState

@Composable
fun PlayerScreen(viewModel: PlayerViewModel = hiltViewModel()) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val sleep by viewModel.sleepState.collectAsStateWithLifecycle()
    val prefs by viewModel.prefs.collectAsStateWithLifecycle()

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text(
            text = state.title.ifEmpty { "Nothing playing" },
            style = MaterialTheme.typography.titleLarge,
        )
        Text("${formatMs(state.positionMs)} / ${formatMs(state.durationMs)}")

        Slider(
            value = if (state.durationMs > 0) state.positionMs.toFloat() / state.durationMs else 0f,
            onValueChange = { f -> viewModel.onSeekTo((f * state.durationMs).toLong()) },
            modifier = Modifier.fillMaxWidth(),
        )

        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            OutlinedButton(onClick = { viewModel.onSeekBy(-10_000) }) { Text("−10s") }
            Button(onClick = viewModel::onPlayPauseClick) {
                Text(if (state.isPlaying) "Pause" else "Play")
            }
            OutlinedButton(onClick = { viewModel.onSeekBy(30_000) }) { Text("+30s") }
        }

        Spacer(Modifier.height(8.dp))
        Text("Speed ${"%.2f".format(state.speed)}×")
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            OutlinedButton(onClick = { viewModel.onSpeedStep(-0.1f) }) { Text("−") }
            OutlinedButton(onClick = { viewModel.onSpeedStep(+0.1f) }) { Text("+") }
            OutlinedButton(onClick = viewModel::onSpeedReset) { Text("Reset") }
        }

        Spacer(Modifier.height(8.dp))
        Text(
            when (val s = sleep) {
                SleepTimerState.Off -> "Sleep timer off"
                is SleepTimerState.Duration -> "Sleeping at ${formatClock(s.endsAtMs)}"
                SleepTimerState.EndOfBoundary -> "Sleeping at end of chapter"
            },
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = { viewModel.onSleepDuration(15) }) { Text("15m") }
            OutlinedButton(onClick = { viewModel.onSleepDuration(30) }) { Text("30m") }
            OutlinedButton(onClick = viewModel::onSleepBoundary) { Text("Chapter") }
            OutlinedButton(onClick = viewModel::onSleepCancel) { Text("Off") }
        }

        Spacer(Modifier.height(8.dp))
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text("Trim silence")
            Switch(checked = prefs.silenceTrim, onCheckedChange = viewModel::onTrimToggle)
        }
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text("Boost ${prefs.boostMb} mB")
            OutlinedButton(onClick = { viewModel.onBoostStep(-300) }) { Text("−") }
            OutlinedButton(onClick = { viewModel.onBoostStep(+300) }) { Text("+") }
        }
    }
}

private fun formatMs(ms: Long): String {
    val totalSec = ms / 1000
    val h = totalSec / 3600
    val m = (totalSec % 3600) / 60
    val s = totalSec % 60
    return if (h > 0) "%d:%02d:%02d".format(h, m, s) else "%d:%02d".format(m, s)
}

private fun formatClock(epochMs: Long): String =
    java.text.SimpleDateFormat("HH:mm", java.util.Locale.US).format(java.util.Date(epochMs))
