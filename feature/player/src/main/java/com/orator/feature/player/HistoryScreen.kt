package com.orator.feature.player

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
fun HistoryScreen(viewModel: HistoryViewModel = hiltViewModel()) {
    val rows by viewModel.rows.collectAsStateWithLifecycle()
    val fmt = SimpleDateFormat("MMM d, HH:mm", Locale.US)

    LazyColumn(modifier = Modifier.fillMaxSize().padding(16.dp)) {
        items(rows, key = { it.id }) { row ->
            Column(Modifier.padding(vertical = 8.dp)) {
                Text(row.title, style = MaterialTheme.typography.titleMedium)
                val end = row.endedAtUtc?.let { fmt.format(Date(it)) } ?: "interrupted"
                val mark = if (row.completed) " ✓" else ""
                Text(
                    "${fmt.format(Date(row.startedAtUtc))} → $end$mark",
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }
    }
}
