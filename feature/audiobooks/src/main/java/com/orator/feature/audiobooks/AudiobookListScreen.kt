package com.orator.feature.audiobooks

import android.content.Intent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.orator.core.database.BookEntity
import com.orator.core.designsystem.components.CoverTile
import com.orator.core.designsystem.components.OnyxTopBar
import com.orator.core.designsystem.icons.OnyxIcons
import com.orator.core.designsystem.shell.LocalShellControls
import com.orator.core.designsystem.text.TimeFormats
import com.orator.core.designsystem.theme.OnyxTokens
import com.orator.core.model.BookOrigin
import com.orator.core.model.DownloadState
import java.io.File

@Composable
fun AudiobookListScreen(
    onOpenBook: (bookId: String) -> Unit,
    onAddToPlaylist: (bookId: String) -> Unit,
    onOpenSearch: () -> Unit,
    viewModel: AudiobookListViewModel = hiltViewModel(),
) {
    val books by viewModel.books.collectAsStateWithLifecycle()
    val hasFolder by viewModel.hasFolder.collectAsStateWithLifecycle()
    val view by viewModel.view.collectAsStateWithLifecycle()
    val sortMode by viewModel.sortMode.collectAsStateWithLifecycle()
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
            trailing = {
                if (books.isNotEmpty()) {
                    Row {
                        SortMenu(current = sortMode, onSelect = viewModel::onSortSelected)
                        IconButton(onClick = onOpenSearch) {
                            Icon(Icons.Filled.Search, "Search", tint = OnyxTokens.TextDim)
                        }
                    }
                }
            },
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
                when (val v = view) {
                    is LibraryView.Flat -> items(v.books, key = { it.id }) { book ->
                        BookGridTile(book, onOpenBook, onAddToPlaylist)
                    }
                    is LibraryView.Sectioned -> v.sections.forEach { section ->
                        item(span = { GridItemSpan(maxLineSpan) }, key = "h:${section.header}") {
                            SectionHeader(section.header)
                        }
                        items(section.books, key = { it.id }) { book ->
                            BookGridTile(book, onOpenBook, onAddToPlaylist)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun BookGridTile(
    book: BookEntity,
    onOpenBook: (String) -> Unit,
    onAddToPlaylist: (String) -> Unit,
) {
    CoverTile(
        // ABS covers are remote URLs (Coil fetches them, authed); local covers
        // are file paths. Wrapping a URL in File would make Coil fail.
        artworkModel = if (book.origin == BookOrigin.ABS) book.coverPath
        else book.coverPath?.let(::File),
        title = book.title,
        // Time-left only once started; no "not started" label (it lives on the detail screen).
        subLine = if (book.positionMs > 0)
            TimeFormats.timeLeft((book.durationMs - book.positionMs).coerceAtLeast(0)) else null,
        progress = if (book.durationMs > 0) book.positionMs.toFloat() / book.durationMs else null,
        onClick = { onOpenBook(book.id) },
        onLongClick = { onAddToPlaylist(book.id) },
        downloaded = book.downloadState == DownloadState.DOWNLOADED,
    )
}

@Composable
private fun SectionHeader(text: String) {
    Text(
        text = text,
        color = OnyxTokens.Text,
        fontSize = 13.sp,
        fontWeight = FontWeight.Bold,
        modifier = Modifier.fillMaxWidth()
            .background(OnyxTokens.Background)
            .padding(start = 8.dp, end = 8.dp, top = 12.dp, bottom = 4.dp),
    )
}

@Composable
private fun SortMenu(current: BookSortMode, onSelect: (BookSortMode) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Box {
        IconButton(onClick = { open = true }) {
            Icon(OnyxIcons.Sort, "Sort", tint = OnyxTokens.TextDim)
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            SortChoice("Recently added", BookSortMode.RECENT, current) { onSelect(it); open = false }
            SortChoice("Title", BookSortMode.TITLE, current) { onSelect(it); open = false }
            SortChoice("Author", BookSortMode.AUTHOR, current) { onSelect(it); open = false }
            SortChoice("Series", BookSortMode.SERIES, current) { onSelect(it); open = false }
        }
    }
}

@Composable
private fun SortChoice(
    label: String, mode: BookSortMode, current: BookSortMode, onPick: (BookSortMode) -> Unit,
) {
    DropdownMenuItem(
        text = { Text(label) },
        onClick = { onPick(mode) },
        leadingIcon = { if (mode == current) Icon(Icons.Filled.Check, null, tint = OnyxTokens.Accent) },
    )
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
