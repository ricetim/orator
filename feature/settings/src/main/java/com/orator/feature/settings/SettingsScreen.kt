package com.orator.feature.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.orator.core.designsystem.components.OnyxTopBar
import com.orator.core.designsystem.components.SectionLabel
import com.orator.core.designsystem.components.SettingsRow
import com.orator.core.designsystem.text.TimeFormats
import com.orator.core.designsystem.theme.OnyxTokens
import com.orator.core.model.MediaType
import kotlin.math.roundToInt

@Composable
fun SettingsScreen(
    onBack: () -> Unit,
    viewModel: SettingsViewModel = hiltViewModel(),
) {
    val prefs by viewModel.state.collectAsStateWithLifecycle()

    Column(Modifier.fillMaxSize().background(OnyxTokens.Background)) {
        OnyxTopBar(
            title = "Settings",
            leadingIcon = Icons.AutoMirrored.Filled.ArrowBack,
            onLeadingClick = onBack,
        )
        Column(
            Modifier.verticalScroll(rememberScrollState())
                .padding(bottom = OnyxTokens.OverlayBottomPadding),
        ) {
            // Feature-owned sections (OPML import, library folders…), in declared order.
            viewModel.sections.sortedBy { it.order }.forEach { section ->
                SectionLabel(section.title)
                section.Content()
            }

            SectionLabel("Playback")
            SpeedRow(
                label = "Global speed",
                value = TimeFormats.speedLabel(prefs.globalSpeed),
                onStep = { delta ->
                    viewModel.setGlobalSpeed(steppedSpeed(prefs.globalSpeed, delta))
                },
            )
            TypeSpeedRow(
                label = "Podcast speed",
                typeSpeed = prefs.perTypeSpeed[MediaType.PODCAST],
                fallback = prefs.globalSpeed,
                onStep = { delta ->
                    val base = prefs.perTypeSpeed[MediaType.PODCAST] ?: prefs.globalSpeed
                    viewModel.setTypeSpeed(MediaType.PODCAST, steppedSpeed(base, delta))
                },
                onClear = { viewModel.setTypeSpeed(MediaType.PODCAST, null) },
            )
            TypeSpeedRow(
                label = "Book speed",
                typeSpeed = prefs.perTypeSpeed[MediaType.AUDIOBOOK],
                fallback = prefs.globalSpeed,
                onStep = { delta ->
                    val base = prefs.perTypeSpeed[MediaType.AUDIOBOOK] ?: prefs.globalSpeed
                    viewModel.setTypeSpeed(MediaType.AUDIOBOOK, steppedSpeed(base, delta))
                },
                onClear = { viewModel.setTypeSpeed(MediaType.AUDIOBOOK, null) },
            )
            SettingsRow(
                glyph = "🔇",
                label = "Trim silence",
                trailing = { OnyxSwitch(prefs.silenceTrim, viewModel::setSilenceTrim) },
            )
            SettingsRow(
                glyph = "🔊",
                label = "Volume boost",
                value = if (prefs.boostMb > 0) "+${prefs.boostMb / 100} dB" else "off",
                trailing = {
                    TextButton(onClick = {
                        viewModel.setBoostMb((prefs.boostMb - 300).coerceAtLeast(0))
                    }) { Text("−") }
                    TextButton(onClick = {
                        viewModel.setBoostMb((prefs.boostMb + 300).coerceAtMost(1500))
                    }) { Text("＋") }
                },
            )
            SettingsRow(
                glyph = "⏪",
                label = "Smart rewind (podcasts)",
                trailing = {
                    OnyxSwitch(prefs.smartRewind[MediaType.PODCAST] == true) {
                        viewModel.setSmartRewind(MediaType.PODCAST, it)
                    }
                },
            )
            SettingsRow(
                glyph = "⏪",
                label = "Smart rewind (books)",
                trailing = {
                    OnyxSwitch(prefs.smartRewind[MediaType.AUDIOBOOK] == true) {
                        viewModel.setSmartRewind(MediaType.AUDIOBOOK, it)
                    }
                },
            )
            SettingsRow(
                glyph = "😴",
                label = "Default sleep timer",
                value = "${prefs.defaultSleepMinutes}m",
                trailing = {
                    TextButton(onClick = {
                        viewModel.setDefaultSleepMinutes(
                            (prefs.defaultSleepMinutes - 5).coerceAtLeast(5),
                        )
                    }) { Text("−") }
                    TextButton(onClick = {
                        viewModel.setDefaultSleepMinutes(prefs.defaultSleepMinutes + 5)
                    }) { Text("＋") }
                },
            )
        }
    }
}

/** ±0.1 steps that survive float error (1.0 − 0.1 stays 0.9, not 0.8). */
private fun steppedSpeed(current: Float, deltaTenths: Int): Float =
    (((current * 10).roundToInt() + deltaTenths) / 10f).coerceIn(0.5f, 3.0f)

@Composable
private fun SpeedRow(label: String, value: String, onStep: (Int) -> Unit) {
    SettingsRow(
        glyph = "⚡",
        label = label,
        value = value,
        trailing = {
            TextButton(onClick = { onStep(-1) }) { Text("−") }
            TextButton(onClick = { onStep(+1) }) { Text("＋") }
        },
    )
}

@Composable
private fun TypeSpeedRow(
    label: String,
    typeSpeed: Float?,
    fallback: Float,
    onStep: (Int) -> Unit,
    onClear: () -> Unit,
) {
    SettingsRow(
        glyph = "⚡",
        label = label,
        value = typeSpeed?.let(TimeFormats::speedLabel)
            ?: "global (${TimeFormats.speedLabel(fallback)})",
        trailing = {
            TextButton(onClick = { onStep(-1) }) { Text("−") }
            TextButton(onClick = { onStep(+1) }) { Text("＋") }
            if (typeSpeed != null) {
                TextButton(onClick = onClear) { Text("✕", color = OnyxTokens.TextFaint) }
            }
        },
    )
}

@Composable
private fun OnyxSwitch(checked: Boolean, onChecked: (Boolean) -> Unit) {
    Switch(
        checked = checked,
        onCheckedChange = onChecked,
        colors = SwitchDefaults.colors(
            checkedTrackColor = OnyxTokens.Accent,
            checkedThumbColor = OnyxTokens.Text,
        ),
    )
}
