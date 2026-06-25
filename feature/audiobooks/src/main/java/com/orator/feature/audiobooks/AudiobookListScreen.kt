package com.orator.feature.audiobooks

import android.content.Intent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.orator.core.designsystem.components.CoverTile
import com.orator.core.designsystem.components.OnyxTopBar
import com.orator.core.designsystem.shell.LocalShellControls
import com.orator.core.designsystem.text.TimeFormats
import com.orator.core.designsystem.theme.OnyxTokens
import com.orator.core.model.BookOrigin
import java.io.File

@Composable
fun AudiobookListScreen(
    onOpenBook: (bookId: String) -> Unit,
    onAddToPlaylist: (bookId: String) -> Unit,
    viewModel: AudiobookListViewModel = hiltViewModel(),
) {
    val books by viewModel.books.collectAsStateWithLifecycle()
    val hasFolder by viewModel.hasFolder.collectAsStateWithLifecycle()
    val shell = LocalShellControls.current
    val context = LocalContext.current

    val pickFolder = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocumentTree(),
    ) { uri ->
        if (uri != null) {
            // Keep the grant across reboots; without this, rescans fail after restart.
            context.contentResolver.takePersistableUriPermission(
                uri,
                Intent.FLAG_GRANT_READ_URI_PERMISSION,
            )
            viewModel.onFolderPicked(uri.toString())
        }
    }

    Column(Modifier.fillMaxSize().background(OnyxTokens.Background)) {
        OnyxTopBar(
            title = "Audiobooks",
            leadingIcon = Icons.Filled.Menu,
            onLeadingClick = shell.openDrawer,
        )
        when {
            !hasFolder -> BooksEmptyState(
                text = "Pick your audiobook folder to build the library",
                buttonLabel = "Choose folder",
                onButton = { pickFolder.launch(null) },
            )
            books.isEmpty() -> BooksEmptyState(
                text = "No books found in the library folder yet",
                buttonLabel = "Rescan",
                onButton = viewModel::onRescan,
            )
            else -> LazyVerticalGrid(
                columns = GridCells.Fixed(3),
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(bottom = OnyxTokens.OverlayBottomPadding),
            ) {
                items(books, key = { it.id }) { book ->
                    CoverTile(
                        // ABS covers are remote URLs (Coil fetches them, authed); local covers
                        // are file paths. Wrapping a URL in File would make Coil fail.
                        artworkModel = if (book.origin == BookOrigin.ABS) {
                            book.coverPath
                        } else {
                            book.coverPath?.let(::File)
                        },
                        title = book.title,
                        // Time-left only once started; no "not started" label (it lives on the detail screen).
                        subLine = if (book.positionMs > 0) {
                            TimeFormats.timeLeft((book.durationMs - book.positionMs).coerceAtLeast(0))
                        } else {
                            null
                        },
                        progress = if (book.durationMs > 0) {
                            book.positionMs.toFloat() / book.durationMs
                        } else {
                            null
                        },
                        onClick = { onOpenBook(book.id) },
                        onLongClick = { onAddToPlaylist(book.id) },
                    )
                }
            }
        }
    }
}

/** Empty-state message + action, vertically centered (user preference). */
@Composable
private fun BooksEmptyState(text: String, buttonLabel: String, onButton: () -> Unit) {
    Column(
        modifier = Modifier.fillMaxSize().padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(16.dp, Alignment.CenterVertically),
    ) {
        Text(text, color = OnyxTokens.TextDim, fontSize = 14.sp, textAlign = TextAlign.Center)
        Button(onClick = onButton) { Text(buttonLabel) }
    }
}
