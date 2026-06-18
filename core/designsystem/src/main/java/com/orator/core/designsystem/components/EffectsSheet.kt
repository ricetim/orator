package com.orator.core.designsystem.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.orator.core.designsystem.text.TimeFormats
import com.orator.core.designsystem.theme.OnyxTokens
import kotlin.math.abs
import kotlin.math.roundToInt

/** Everything the sheet renders; hosts map their domain state into this. */
data class EffectsSheetState(
    val title: String,                       // "Effects — The Daily Brief"
    val speed: Float,                        // effective speed shown/highlighted
    val overrideEnabled: Boolean,            // per-item override switch
    val trimOn: Boolean,
    val boostMb: Int,                        // 0 = off
    /** null = hide the intro/outro section (books). */
    val clip: Pair<Long, Long>?,             // intro ms, outro ms
)

private val SPEED_PRESETS = listOf(1.0f, 1.2f, 1.5f, 1.7f, 2.0f)
private const val BOOST_STEP_MB = 300
private const val BOOST_MAX_MB = 1500
private const val BOOST_DEFAULT_MB = 300
private const val CLIP_STEP_MS = 15_000L
private const val CLIP_DEFAULT_MS = 30_000L

/**
 * The ONE effects editor (mockup .sheet): identical for podcasts and books; the host decides
 * which prefs/fields the callbacks write. All rows always visible except intro/outro
 * (podcast contexts only).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EffectsSheet(
    state: EffectsSheetState,
    onDismiss: () -> Unit,
    onSpeed: (Float) -> Unit,
    onOverrideToggle: (Boolean) -> Unit,
    onTrim: (Boolean) -> Unit,
    onBoost: (Int) -> Unit,                 // new mB value
    onClip: (Long, Long) -> Unit,           // new intro/outro ms
) {
    ModalBottomSheet(onDismissRequest = onDismiss, containerColor = OnyxTokens.Surface) {
        Column(Modifier.padding(bottom = 16.dp)) {
            Text(
                state.title,
                color = OnyxTokens.Text,
                fontSize = 15.sp,
                fontWeight = FontWeight.ExtraBold,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth(),
            )

            // Speed: −/＋ 0.1 steps flanking preset chips; non-preset shows as the live label.
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 14.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                TextButton(onClick = { onSpeed(((state.speed * 10).roundToInt() - 1) / 10f) }) { Text("−") }
                SPEED_PRESETS.forEach { preset ->
                    val selected = abs(state.speed - preset) < 0.01f
                    OutlinedButton(
                        onClick = { onSpeed(preset) },
                        border = BorderStroke(1.dp, if (selected) OnyxTokens.Accent else OnyxTokens.ChipBorder),
                        modifier = Modifier.weight(1f),
                        contentPadding = PaddingValues(0.dp),
                    ) {
                        Text(
                            TimeFormats.speedLabel(preset),
                            fontSize = 12.sp,
                            color = if (selected) OnyxTokens.Accent else OnyxTokens.TextDim,
                        )
                    }
                }
                TextButton(onClick = { onSpeed(((state.speed * 10).roundToInt() + 1) / 10f) }) { Text("＋") }
            }
            if (SPEED_PRESETS.none { abs(state.speed - it) < 0.01f }) {
                Text(
                    TimeFormats.speedLabel(state.speed),
                    color = OnyxTokens.Accent,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Bold,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth(),
                )
            }

            ToggleRow("Trim silence", "applies everywhere", state.trimOn, onTrim)

            // Boost: toggle (OFF→0, ON→300 if 0) + steppers while on.
            ToggleRow(
                title = "Volume boost",
                sub = if (state.boostMb > 0) "+${state.boostMb / 100} dB" else "normalize & lift vocals",
                checked = state.boostMb > 0,
                onChecked = { on -> onBoost(if (on) BOOST_DEFAULT_MB else 0) },
            ) {
                if (state.boostMb > 0) {
                    TextButton(onClick = { onBoost((state.boostMb - BOOST_STEP_MB).coerceAtLeast(0)) }) { Text("−") }
                    TextButton(onClick = { onBoost((state.boostMb + BOOST_STEP_MB).coerceAtMost(BOOST_MAX_MB)) }) { Text("＋") }
                }
            }

            // Intro/outro skip: per-show only; hidden for books.
            if (state.clip != null) {
                val (intro, outro) = state.clip
                ToggleRow(
                    title = "Skip intro / outro",
                    sub = "−${intro / 1000}s start · −${outro / 1000}s end",
                    checked = intro > 0 || outro > 0,
                    onChecked = { on ->
                        if (on) onClip(CLIP_DEFAULT_MS, CLIP_DEFAULT_MS) else onClip(0, 0)
                    },
                )
                if (intro > 0 || outro > 0) {
                    ClipStepperRow("Intro", intro) { newIntro -> onClip(newIntro, outro) }
                    ClipStepperRow("Outro", outro) { newOutro -> onClip(intro, newOutro) }
                }
            }

            ToggleRow(
                "Override for this show / book only", "speed only",
                state.overrideEnabled, onOverrideToggle,
            )
        }
    }
}

@Composable
private fun ClipStepperRow(label: String, valueMs: Long, onValue: (Long) -> Unit) {
    Row(
        Modifier.padding(horizontal = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, color = OnyxTokens.TextDim, fontSize = 12.sp, modifier = Modifier.weight(1f))
        TextButton(onClick = { onValue((valueMs - CLIP_STEP_MS).coerceAtLeast(0)) }) { Text("−15s") }
        TextButton(onClick = { onValue(valueMs + CLIP_STEP_MS) }) { Text("＋15s") }
    }
}

@Composable
private fun ToggleRow(
    title: String,
    sub: String,
    checked: Boolean,
    onChecked: (Boolean) -> Unit,
    extras: @Composable () -> Unit = {},
) {
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, color = OnyxTokens.Text, fontSize = 14.5.sp, fontWeight = FontWeight.Bold)
            Text(sub, color = OnyxTokens.TextDim, fontSize = 11.5.sp)
        }
        extras()
        Switch(
            checked = checked,
            onCheckedChange = onChecked,
            colors = SwitchDefaults.colors(
                checkedTrackColor = OnyxTokens.Accent,
                checkedThumbColor = OnyxTokens.Text,
            ),
        )
    }
}
