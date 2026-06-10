package com.orator.feature.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.orator.core.model.MediaType

@Composable
fun SettingsScreen(viewModel: SettingsViewModel = hiltViewModel()) {
    val p by viewModel.state.collectAsStateWithLifecycle()

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text("Playback speed", style = MaterialTheme.typography.titleMedium)
        Stepper(
            label = "Global: ${"%.2f".format(p.globalSpeed)}×",
            onMinus = { viewModel.setGlobalSpeed((p.globalSpeed - 0.1f).coerceAtLeast(0.5f)) },
            onPlus = { viewModel.setGlobalSpeed((p.globalSpeed + 0.1f).coerceAtMost(3.0f)) },
        )
        MediaType.entries.forEach { t ->
            val v = p.perTypeSpeed[t]
            Stepper(
                label = "${t.name.lowercase()}: ${v?.let { "%.2f×".format(it) } ?: "global"}",
                onMinus = {
                    viewModel.setTypeSpeed(t, ((v ?: p.globalSpeed) - 0.1f).coerceAtLeast(0.5f))
                },
                onPlus = {
                    viewModel.setTypeSpeed(t, ((v ?: p.globalSpeed) + 0.1f).coerceAtMost(3.0f))
                },
                extra = { OutlinedButton(onClick = { viewModel.setTypeSpeed(t, null) }) { Text("Clear") } },
            )
        }

        Text("Effects", style = MaterialTheme.typography.titleMedium)
        LabeledSwitch("Trim silence", p.silenceTrim, viewModel::setSilenceTrim)
        Stepper(
            label = "Volume boost: ${p.boostMb} mB",
            onMinus = { viewModel.setBoostMb((p.boostMb - 300).coerceAtLeast(0)) },
            onPlus = { viewModel.setBoostMb((p.boostMb + 300).coerceAtMost(1500)) },
        )

        Text("Smart rewind on resume", style = MaterialTheme.typography.titleMedium)
        MediaType.entries.forEach { t ->
            LabeledSwitch(t.name.lowercase(), p.smartRewind[t] ?: true) { on ->
                viewModel.setSmartRewind(t, on)
            }
        }

        Text("Sleep timer", style = MaterialTheme.typography.titleMedium)
        Stepper(
            label = "Default: ${p.defaultSleepMinutes} min",
            onMinus = { viewModel.setDefaultSleepMinutes((p.defaultSleepMinutes - 15).coerceAtLeast(15)) },
            onPlus = { viewModel.setDefaultSleepMinutes((p.defaultSleepMinutes + 15).coerceAtMost(120)) },
        )
    }
}

@Composable
private fun Stepper(
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

@Composable
private fun LabeledSwitch(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, modifier = Modifier.weight(1f))
        Switch(checked = checked, onCheckedChange = onChange)
    }
}
