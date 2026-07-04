package com.orator.core.designsystem.components

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.orator.core.designsystem.icons.OnyxIcons
import com.orator.core.designsystem.theme.OnyxTokens

/**
 * Flush square grid tile: artwork under a bottom caption scrim, optional progress strip
 * along the bottom edge, optional downloaded badge (top-start). Mockup .tile/.cap/.pprog —
 * zero gaps, zero corner radius. Caption colors stay white/light-gray: they sit on imagery,
 * not on theme surfaces.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun CoverTile(
    artworkModel: Any?,
    title: String,
    subLine: String?,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    progress: Float? = null,
    onLongClick: (() -> Unit)? = null,
    downloaded: Boolean = false,
) {
    Box(modifier = modifier.aspectRatio(1f).combinedClickable(onClick = onClick, onLongClick = onLongClick)) {
        ArtworkImage(model = artworkModel, title = title, modifier = Modifier.fillMaxSize())
        Box(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .background(
                    Brush.verticalGradient(
                        listOf(Color.Transparent, Color.Black.copy(alpha = 0.78f)),
                    ),
                )
                .padding(start = 7.dp, end = 7.dp, top = 20.dp, bottom = 6.dp),
        ) {
            Column {
                Text(
                    text = title,
                    color = Color.White,
                    fontSize = 10.5.sp,
                    fontWeight = FontWeight.Bold,
                    lineHeight = 13.sp,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                if (subLine != null) {
                    Text(
                        text = subLine,
                        color = Color(0xFFC4C8CD),
                        fontSize = 9.5.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
        if (progress != null && progress > 0f) {
            Box(
                Modifier.align(Alignment.BottomCenter).fillMaxWidth().height(3.dp)
                    .background(Color.White.copy(alpha = 0.18f)),
            ) {
                Box(
                    Modifier.fillMaxWidth(progress.coerceIn(0f, 1f)).fillMaxSize()
                        .background(OnyxTokens.Accent),
                )
            }
        }
        if (downloaded) {
            Box(
                Modifier.align(Alignment.TopStart).padding(4.dp)
                    .background(Color.Black.copy(alpha = 0.55f), CircleShape).padding(2.dp),
            ) {
                Icon(
                    OnyxIcons.Downloaded,
                    contentDescription = "Downloaded",
                    tint = Color.White,
                    modifier = Modifier.size(13.dp),
                )
            }
        }
    }
}
