package com.orator.feature.player

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.orator.core.designsystem.theme.OnyxTokens
import com.orator.core.playback.SleepTimerState
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Sleep timer sheet: duration presets, end-of-boundary, off. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SleepSheet(
    sleep: SleepTimerState,
    isBook: Boolean,
    onDuration: (Int) -> Unit,
    onBoundary: () -> Unit,
    onCancel: () -> Unit,
    onDismiss: () -> Unit,
) {
    ModalBottomSheet(onDismissRequest = onDismiss, containerColor = OnyxTokens.Surface) {
        Column(Modifier.padding(bottom = 24.dp)) {
            Text(
                "Sleep timer",
                color = OnyxTokens.Text,
                fontSize = 15.sp,
                fontWeight = FontWeight.ExtraBold,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth(),
            )
            Text(
                text = when (val s = sleep) {
                    SleepTimerState.Off -> "off"
                    is SleepTimerState.Duration -> "sleeping at ${formatClock(s.endsAtMs)}"
                    SleepTimerState.EndOfBoundary ->
                        if (isBook) "at end of chapter" else "at end of episode"
                },
                color = OnyxTokens.TextFaint,
                fontSize = 11.sp,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth().padding(bottom = 10.dp),
            )
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 16.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                listOf(15, 30, 45, 60).forEach { minutes ->
                    OutlinedButton(
                        onClick = { onDuration(minutes); onDismiss() },
                        modifier = Modifier.weight(1f),
                    ) {
                        Text("${minutes}m", fontSize = 12.sp, color = OnyxTokens.TextDim)
                    }
                }
            }
            TextButton(
                onClick = { onBoundary(); onDismiss() },
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
            ) {
                Text(
                    if (isBook) "End of chapter" else "End of episode",
                    color = OnyxTokens.AccentBright,
                )
            }
            TextButton(
                onClick = { onCancel(); onDismiss() },
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
            ) {
                Text("Off", color = OnyxTokens.TextDim)
            }
        }
    }
}

internal fun formatClock(epochMs: Long): String =
    SimpleDateFormat("HH:mm", Locale.US).format(Date(epochMs))
