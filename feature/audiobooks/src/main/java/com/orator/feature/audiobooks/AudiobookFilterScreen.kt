package com.orator.feature.audiobooks

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.orator.core.designsystem.components.OnyxTopBar
import com.orator.core.designsystem.theme.OnyxTokens

@Composable
fun AudiobookFilterScreen(
    onOpenBook: (String) -> Unit,
    onAddToPlaylist: (String) -> Unit,
    onBack: () -> Unit,
    viewModel: AudiobookFilterViewModel = hiltViewModel(),
) {
    val books by viewModel.books.collectAsStateWithLifecycle()

    Column(Modifier.fillMaxSize().background(OnyxTokens.Background)) {
        OnyxTopBar(
            title = viewModel.value,
            leadingIcon = Icons.AutoMirrored.Filled.ArrowBack,
            onLeadingClick = onBack,
        )
        LazyVerticalGrid(
            columns = GridCells.Fixed(3),
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(bottom = OnyxTokens.OverlayBottomPadding),
        ) {
            items(books, key = { it.id }) { book ->
                BookGridTile(book, onOpenBook, onAddToPlaylist)
            }
        }
    }
}
