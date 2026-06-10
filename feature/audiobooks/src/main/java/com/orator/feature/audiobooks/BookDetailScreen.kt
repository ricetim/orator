package com.orator.feature.audiobooks

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle

@Composable
fun BookDetailScreen(viewModel: BookDetailViewModel = hiltViewModel()) {
    val book by viewModel.book.collectAsStateWithLifecycle()
    val chapters by viewModel.chapters.collectAsStateWithLifecycle()
    val bookmarks by viewModel.bookmarks.collectAsStateWithLifecycle()
    val playback by viewModel.playback.collectAsStateWithLifecycle()

    val b = book ?: return
    val playingThis = viewModel.isThisBook(playback)

    Column(modifier = Modifier.fillMaxSize().padding(16.dp)) {
        Text(text = b.title)
        Text(text = b.author ?: "Unknown author")
        Text(text = "Position: ${b.positionMs / 1000}s / ${b.durationMs / 1000}s")

        Row {
            Button(onClick = viewModel::onPlayResume) {
                Text(if (b.positionMs > 0) "Resume" else "Play")
            }
            if (playingThis) {
                OutlinedButton(onClick = viewModel::onPlayPause) {
                    Text(if (playback.isPlaying) "Pause" else "Continue")
                }
            }
            OutlinedButton(onClick = viewModel::onAddBookmark) { Text("Bookmark") }
        }

        LazyColumn {
            if (bookmarks.isNotEmpty()) {
                item { Text(text = "Bookmarks", modifier = Modifier.padding(top = 12.dp)) }
                items(bookmarks, key = { "bm-${it.id}" }) { bm ->
                    Row(modifier = Modifier.fillMaxWidth()) {
                        Text(
                            text = "@ ${bm.positionMs / 1000}s",
                            modifier = Modifier
                                .clickable { viewModel.onBookmarkClick(bm) }
                                .padding(vertical = 8.dp),
                        )
                        Text(
                            text = "  ✕",
                            modifier = Modifier
                                .clickable { viewModel.onDeleteBookmark(bm.id) }
                                .padding(vertical = 8.dp),
                        )
                    }
                }
            }

            item { Text(text = "Chapters", modifier = Modifier.padding(top = 12.dp)) }
            items(chapters, key = { "ch-${it.chapterIndex}" }) { chapter ->
                Text(
                    text = chapter.title,
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { viewModel.onChapterClick(chapter) }
                        .padding(vertical = 8.dp),
                )
            }
        }
    }
}
