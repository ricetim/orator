package com.orator.feature.player

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.orator.core.designsystem.components.EpisodeRow
import com.orator.core.designsystem.components.OnyxTopBar
import com.orator.core.designsystem.components.SectionLabel
import com.orator.core.designsystem.shell.LocalShellControls
import com.orator.core.designsystem.theme.OnyxTokens

/** Read-only queue tab (mockup #queue, minus swipes — those arrive with playlists). */
@Composable
fun QueueScreen(viewModel: QueueViewModel = hiltViewModel()) {
    val ui by viewModel.ui.collectAsStateWithLifecycle()
    val shell = LocalShellControls.current

    Column(Modifier.fillMaxSize().background(OnyxTokens.Background)) {
        OnyxTopBar(
            title = "Queue",
            leadingIcon = Icons.Filled.Menu,
            onLeadingClick = shell.openDrawer,
        )
        Text(
            "Read-only for now — the full mixed queue arrives with playlists",
            color = OnyxTokens.TextFaint,
            fontSize = 11.sp,
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth().padding(horizontal = 18.dp, vertical = 2.dp),
        )
        if (!ui.loaded) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text("Nothing playing", color = OnyxTokens.TextDim, fontSize = 14.sp)
            }
            return@Column
        }
        val current = ui.rows.firstOrNull { it.isCurrent }
        val next = ui.rows.dropWhile { !it.isCurrent }.drop(1)
        LazyColumn(contentPadding = PaddingValues(bottom = OnyxTokens.OverlayBottomPadding)) {
            if (current != null) {
                item { SectionLabel("Now playing") }
                item {
                    EpisodeRow(
                        title = current.title,
                        subLine = current.subLine,
                        onClick = {},
                        titleColor = OnyxTokens.AccentBright,
                        trailing = { Text("▶", color = OnyxTokens.Accent, fontSize = 14.sp) },
                    )
                }
            }
            item { SectionLabel("Next · ${next.size}") }
            items(next) { row ->
                EpisodeRow(
                    title = row.title,
                    subLine = row.subLine,
                    onClick = { viewModel.onJump(row.seekTarget) },
                )
            }
        }
    }
}
