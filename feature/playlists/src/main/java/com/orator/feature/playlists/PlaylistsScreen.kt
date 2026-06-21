package com.orator.feature.playlists

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.orator.core.designsystem.components.EpisodeRow
import com.orator.core.designsystem.components.OnyxTopBar
import com.orator.core.designsystem.icons.OnyxIcons
import com.orator.core.designsystem.shell.LocalShellControls
import com.orator.core.designsystem.theme.OnyxTokens

@Composable
fun PlaylistsScreen(
    onOpenPlaylist: (Long) -> Unit,
    viewModel: PlaylistsViewModel = hiltViewModel(),
) {
    val playlists by viewModel.playlists.collectAsStateWithLifecycle()
    val shell = LocalShellControls.current
    var showCreate by remember { mutableStateOf(false) }

    Column(Modifier.fillMaxSize().background(OnyxTokens.Background)) {
        OnyxTopBar(
            title = "Playlists",
            leadingIcon = Icons.Filled.Menu,
            onLeadingClick = shell.openDrawer,
            trailing = {
                IconButton(onClick = { showCreate = true }) {
                    Icon(OnyxIcons.Add, contentDescription = "New playlist", tint = OnyxTokens.TextDim)
                }
            },
        )
        if (playlists.isEmpty()) {
            PlaylistEmptyState(
                text = "No playlists yet — create one to mix episodes and books",
                buttonLabel = "New playlist",
                onButton = { showCreate = true },
            )
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(bottom = OnyxTokens.OverlayBottomPadding),
            ) {
                items(playlists, key = { it.id }) { playlist ->
                    EpisodeRow(
                        title = playlist.name,
                        subLine = itemCountLabel(playlist.itemCount),
                        onClick = { onOpenPlaylist(playlist.id) },
                    )
                }
            }
        }
    }

    if (showCreate) {
        NameDialog(
            title = "New playlist",
            confirmLabel = "Create",
            onConfirm = { name -> viewModel.create(name); showCreate = false },
            onDismiss = { showCreate = false },
        )
    }
}
