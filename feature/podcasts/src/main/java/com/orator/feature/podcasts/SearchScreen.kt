package com.orator.feature.podcasts

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle

@Composable
fun SearchScreen(viewModel: SearchViewModel = hiltViewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    var term by remember { mutableStateOf("") }

    Column(
        modifier = Modifier.fillMaxSize().padding(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text("Search podcasts")
        Row(verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(
                value = term,
                onValueChange = { term = it },
                label = { Text("Search term") },
                modifier = Modifier.weight(1f),
            )
            Button(onClick = { viewModel.onSearch(term) }) { Text("Search") }
        }
        if (state.searching) Text("Searching…")
        state.provider?.let { Text("via $it") }
        state.error?.let { Text(it) }
        LazyColumn(modifier = Modifier.weight(1f)) {
            items(state.results, key = { it.feedUrl }) { result ->
                Row(
                    modifier = Modifier.fillMaxWidth().padding(vertical = 12.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(result.title)
                        Text(result.author ?: "")
                    }
                    val subscribed = result.feedUrl in state.subscribedFeeds
                    OutlinedButton(
                        onClick = { viewModel.onSubscribe(result) },
                        enabled = !subscribed,
                    ) {
                        Text(if (subscribed) "Subscribed" else "Subscribe")
                    }
                }
            }
        }
    }
}
