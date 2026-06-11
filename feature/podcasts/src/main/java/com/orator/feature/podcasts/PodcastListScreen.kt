package com.orator.feature.podcasts

import android.content.Intent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.orator.core.database.PodcastEntity

@Composable
fun PodcastListScreen(
    onPodcastClick: (String) -> Unit,
    onOpenPlayer: () -> Unit,
    viewModel: PodcastListViewModel = hiltViewModel(),
) {
    val podcasts by viewModel.podcasts.collectAsStateWithLifecycle()
    val hasFolder by viewModel.hasFolder.collectAsStateWithLifecycle()
    val busy by viewModel.busy.collectAsStateWithLifecycle()
    val lastResult by viewModel.lastResult.collectAsStateWithLifecycle()
    val playback by viewModel.playback.collectAsStateWithLifecycle()
    val context = LocalContext.current
    var feedUrl by remember { mutableStateOf("") }

    val pickFolder = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocumentTree(),
    ) { uri ->
        if (uri != null) {
            context.contentResolver.takePersistableUriPermission(
                uri,
                Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION,
            )
            viewModel.onFolderPicked(uri.toString())
        }
    }
    val pickOpml = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument(),
    ) { uri ->
        if (uri != null) viewModel.onImportOpml(uri)
    }

    Column(
        modifier = Modifier.fillMaxSize().padding(16.dp),
        // Menus sit centered (user preference for test UIs); the show list still fills width.
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text("Podcasts")
        Row(horizontalArrangement = Arrangement.Center) {
            Button(onClick = { pickFolder.launch(null) }) {
                Text(if (hasFolder) "Change folder" else "Choose podcast folder")
            }
            OutlinedButton(onClick = { pickOpml.launch(arrayOf("*/*")) }) { Text("Import OPML") }
            OutlinedButton(onClick = viewModel::onRefreshAll) { Text("Refresh all") }
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(
                value = feedUrl,
                onValueChange = { feedUrl = it },
                label = { Text("Feed URL") },
                modifier = Modifier.weight(1f),
            )
            Button(onClick = { viewModel.onAddFeed(feedUrl); feedUrl = "" }) { Text("Add") }
        }
        busy?.let { Text(it) }
        lastResult?.let { Text(it) }
        LazyColumn(modifier = Modifier.weight(1f)) {
            items(podcasts, key = PodcastEntity::id) { podcast ->
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { onPodcastClick(podcast.id) }
                        .padding(vertical = 12.dp),
                ) {
                    Text(podcast.title)
                    Text(podcast.author ?: "Unknown author")
                }
            }
        }
        if (playback.title.isNotEmpty()) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable(onClick = onOpenPlayer)
                    .padding(vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = (if (playback.isPlaying) "▶ " else "⏸ ") + playback.title,
                    maxLines = 1,
                )
            }
        }
    }
}
