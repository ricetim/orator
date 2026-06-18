package com.orator.core.designsystem.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.orator.core.designsystem.icons.OnyxIcons
import com.orator.core.designsystem.theme.OnyxTokens

/**
 * Docked edge-to-edge mini player: 2dp progress strip on the top edge, 38dp art, title,
 * subline, play/pause. NOT floating — sits flush above the nav bar (or the screen bottom).
 * Mockup .mini (final-tweak version).
 */
@Composable
fun MiniPlayer(
    title: String,
    subLine: String,
    progress: Float,
    isPlaying: Boolean,
    artworkModel: Any?,
    onClick: () -> Unit,
    onPlayPause: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier.fillMaxWidth().background(OnyxTokens.Surface).clickable(onClick = onClick),
    ) {
        Box(Modifier.fillMaxWidth().height(2.dp).background(OnyxTokens.SurfaceBorder)) {
            Box(
                Modifier.fillMaxWidth(progress.coerceIn(0f, 1f)).fillMaxSize()
                    .background(OnyxTokens.Accent),
            )
        }
        Row(
            modifier = Modifier.fillMaxWidth().height(OnyxTokens.MiniPlayerHeight)
                .padding(horizontal = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            ArtworkImage(
                model = artworkModel,
                title = title,
                modifier = Modifier.size(38.dp),
                cornerRadius = 8.dp,
                initialsSize = 13.sp,
            )
            Column(Modifier.weight(1f).padding(horizontal = 11.dp)) {
                Text(
                    title,
                    color = OnyxTokens.Text,
                    fontSize = 12.5.sp,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(subLine, color = OnyxTokens.TextDim, fontSize = 10.5.sp, maxLines = 1)
            }
            IconButton(onClick = onPlayPause) {
                Icon(
                    if (isPlaying) OnyxIcons.Pause else Icons.Filled.PlayArrow,
                    contentDescription = if (isPlaying) "Pause" else "Play",
                    tint = OnyxTokens.Text,
                )
            }
        }
    }
}
