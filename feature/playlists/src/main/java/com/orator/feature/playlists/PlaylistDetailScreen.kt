package com.orator.feature.playlists

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.orator.core.designsystem.components.EpisodeRow
import com.orator.core.designsystem.components.OnyxTopBar
import com.orator.core.designsystem.components.RowArt
import com.orator.core.designsystem.components.SwipeActionRow
import com.orator.core.designsystem.icons.OnyxIcons
import com.orator.core.designsystem.text.TimeFormats
import com.orator.core.designsystem.theme.OnyxTokens
import com.orator.feature.playlists.data.PlaylistItemUi
import kotlin.math.roundToInt

private val RowHeight = 64.dp

@Composable
fun PlaylistDetailScreen(
    onOpenPlayer: () -> Unit,
    onBack: () -> Unit,
    viewModel: PlaylistDetailViewModel = hiltViewModel(),
) {
    val name by viewModel.name.collectAsStateWithLifecycle()
    val items by viewModel.items.collectAsStateWithLifecycle()
    var menuOpen by remember { mutableStateOf(false) }
    var renaming by remember { mutableStateOf(false) }

    // Lift-and-drop reorder state.
    var draggingId by remember { mutableStateOf<Long?>(null) }
    var dragDelta by remember { mutableFloatStateOf(0f) }
    val rowHeightPx = with(LocalDensity.current) { RowHeight.toPx() }

    Column(Modifier.fillMaxWidth().background(OnyxTokens.Background)) {
        OnyxTopBar(
            title = name,
            leadingIcon = Icons.AutoMirrored.Filled.ArrowBack,
            onLeadingClick = onBack,
            trailing = {
                IconButton(onClick = { menuOpen = true }) {
                    Icon(OnyxIcons.More, contentDescription = "More", tint = OnyxTokens.TextDim)
                }
                DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                    DropdownMenuItem(
                        text = { Text("Rename") },
                        onClick = { menuOpen = false; renaming = true },
                    )
                    DropdownMenuItem(
                        text = { Text("Delete playlist") },
                        onClick = { menuOpen = false; viewModel.delete(); onBack() },
                    )
                }
            },
        )

        PlayFromTopBar(
            enabled = items.isNotEmpty(),
            onClick = { viewModel.playFromTop(); onOpenPlayer() },
        )

        if (items.isEmpty()) {
            PlaylistEmptyState(text = "This playlist is empty", buttonLabel = null, onButton = {})
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxWidth(),
                contentPadding = PaddingValues(bottom = OnyxTokens.OverlayBottomPadding),
            ) {
                itemsIndexed(items, key = { _, it -> it.itemId }) { _, ui ->
                    PlaylistItemRow(
                        ui = ui,
                        dragging = ui.itemId == draggingId,
                        dragDelta = dragDelta,
                        gesturesEnabled = draggingId == null,
                        onTap = { viewModel.playItem(ui.itemId) },
                        onRemove = { viewModel.remove(ui.itemId) },
                        onDragStart = { draggingId = ui.itemId; dragDelta = 0f },
                        onDrag = { dragDelta += it },
                        onDragEnd = {
                            val from = items.indexOfFirst { it.itemId == ui.itemId }
                            if (from >= 0) {
                                val to = (from + (dragDelta / rowHeightPx).roundToInt())
                                    .coerceIn(0, items.lastIndex)
                                if (to != from) viewModel.move(from, to)
                            }
                            draggingId = null; dragDelta = 0f
                        },
                        onDragCancel = { draggingId = null; dragDelta = 0f },
                    )
                }
            }
        }
    }

    if (renaming) {
        NameDialog(
            title = "Rename playlist",
            confirmLabel = "Rename",
            initial = name,
            onConfirm = { viewModel.rename(it); renaming = false },
            onDismiss = { renaming = false },
        )
    }
}

@Composable
private fun PlayFromTopBar(enabled: Boolean, onClick: () -> Unit) {
    val tint = if (enabled) OnyxTokens.Accent else OnyxTokens.TextFaint
    Row(
        Modifier.fillMaxWidth().clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(Icons.Filled.PlayArrow, contentDescription = null, tint = tint)
        Spacer(Modifier.width(8.dp))
        Text("Play from top", color = tint, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
    }
}

@Composable
private fun PlaylistItemRow(
    ui: PlaylistItemUi,
    dragging: Boolean,
    dragDelta: Float,
    gesturesEnabled: Boolean,
    onTap: () -> Unit,
    onRemove: () -> Unit,
    onDragStart: () -> Unit,
    onDrag: (Float) -> Unit,
    onDragEnd: () -> Unit,
    onDragCancel: () -> Unit,
) {
    val subLine = if (ui.content.durationMs > 0) {
        "${ui.content.subtitle} · ${TimeFormats.clock(ui.content.durationMs)}"
    } else {
        ui.content.subtitle
    }

    val rowModifier = if (dragging) {
        Modifier.graphicsLayer { translationY = dragDelta; shadowElevation = 12f }.zIndex(1f)
    } else {
        Modifier
    }

    SwipeActionRow(
        enabled = gesturesEnabled,
        actionLabel = "Remove ✕",
        onSwipeLeft = onRemove,
        modifier = rowModifier,
    ) {
        EpisodeRow(
            title = ui.content.title,
            subLine = subLine,
            onClick = { if (gesturesEnabled) onTap() },
            modifier = Modifier.height(RowHeight),
            leading = { RowArt(ui.content.artworkUri, ui.content.title) },
            trailing = {
                Box(
                    Modifier.size(40.dp).pointerInput(ui.itemId) {
                        detectDragGesturesAfterLongPress(
                            onDragStart = { onDragStart() },
                            onDrag = { change, drag -> change.consume(); onDrag(drag.y) },
                            onDragEnd = { onDragEnd() },
                            onDragCancel = { onDragCancel() },
                        )
                    },
                    contentAlignment = Alignment.Center,
                ) {
                    Text("≡", color = OnyxTokens.TextFaint, fontSize = 20.sp)
                }
            },
        )
    }
}
