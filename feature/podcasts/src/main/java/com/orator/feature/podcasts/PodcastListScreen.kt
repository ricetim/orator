package com.orator.feature.podcasts

import android.content.Intent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
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

@Composable
fun PodcastListScreen(
    onPodcastClick: (String) -> Unit,
    viewModel: PodcastListViewModel = hiltViewModel(),
) {
    val podcasts by viewModel.podcasts.collectAsStateWithLifecycle()
    val latestPub by viewModel.latestPub.collectAsStateWithLifecycle()
    val hasFolder by viewModel.hasFolder.collectAsStateWithLifecycle()
    val busy by viewModel.busy.collectAsStateWithLifecycle()
    val lastResult by viewModel.lastResult.collectAsStateWithLifecycle()
    val shell = LocalShellControls.current
    val context = LocalContext.current

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

    Column(Modifier.fillMaxSize().background(OnyxTokens.Background)) {
        OnyxTopBar(
            title = "Podcasts",
            leadingIcon = Icons.Filled.Menu,
            onLeadingClick = shell.openDrawer,
            trailing = {
                if (busy != null) {
                    CircularProgressIndicator(
                        Modifier.size(20.dp),
                        strokeWidth = 2.dp,
                        color = OnyxTokens.Accent,
                    )
                } else {
                    IconButton(onClick = viewModel::onRefreshAll) {
                        Icon(Icons.Filled.Refresh, "Refresh", tint = OnyxTokens.TextDim)
                    }
                }
            },
        )
        val status = busy ?: lastResult
        if (status != null) {
            Text(
                status,
                color = OnyxTokens.TextFaint,
                fontSize = 11.sp,
                modifier = Modifier.padding(horizontal = 16.dp),
            )
        }
        when {
            !hasFolder -> CenteredEmptyState(
                text = "Pick a storage folder to start subscribing",
                buttonLabel = "Choose folder",
                onButton = { pickFolder.launch(null) },
            )
            podcasts.isEmpty() -> CenteredEmptyState(
                text = "No subscriptions yet — search or add a feed from the menu",
                buttonLabel = null,
                onButton = {},
            )
            else -> LazyVerticalGrid(
                columns = GridCells.Fixed(3),
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(bottom = OnyxTokens.OverlayBottomPadding),
            ) {
                items(podcasts, key = { it.id }) { podcast ->
                    CoverTile(
                        artworkModel = podcast.artworkUrl,
                        title = podcast.title,
                        subLine = latestPub[podcast.id]?.let(TimeFormats::relativeDay),
                        onClick = { onPodcastClick(podcast.id) },
                    )
                }
            }
        }
    }
}

/** Empty-state message + optional action, vertically centered (user preference). */
@Composable
internal fun CenteredEmptyState(
    text: String,
    buttonLabel: String?,
    onButton: () -> Unit,
) {
    Column(
        modifier = Modifier.fillMaxSize().padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(16.dp, Alignment.CenterVertically),
    ) {
        Text(text, color = OnyxTokens.TextDim, fontSize = 14.sp, textAlign = TextAlign.Center)
        if (buttonLabel != null) {
            Button(onClick = onButton) { Text(buttonLabel) }
        }
    }
}
