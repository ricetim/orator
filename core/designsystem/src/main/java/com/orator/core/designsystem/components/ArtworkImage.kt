package com.orator.core.designsystem.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil3.compose.SubcomposeAsyncImage

/**
 * Artwork from any source Coil understands (File, content:// URI, https URL), with the
 * initials-on-gradient fallback while loading / on error / when [model] is null.
 */
@Composable
fun ArtworkImage(
    model: Any?,
    title: String,
    modifier: Modifier = Modifier,
    cornerRadius: Dp = 0.dp,
    initialsSize: TextUnit = 22.sp,
) {
    val shaped = if (cornerRadius > 0.dp) modifier.clip(RoundedCornerShape(cornerRadius)) else modifier
    if (model == null) {
        FallbackArt(title, shaped, initialsSize)
    } else {
        SubcomposeAsyncImage(
            model = model,
            contentDescription = title,
            contentScale = ContentScale.Crop,
            modifier = shaped,
            loading = { FallbackArt(title, Modifier.fillMaxSize(), initialsSize) },
            error = { FallbackArt(title, Modifier.fillMaxSize(), initialsSize) },
        )
    }
}

@Composable
private fun FallbackArt(title: String, modifier: Modifier, initialsSize: TextUnit) {
    val (start, end) = ArtworkFallback.gradientFor(title)
    Box(
        modifier = modifier.background(Brush.linearGradient(listOf(start, end))),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = ArtworkFallback.initials(title),
            color = Color.White,
            fontSize = initialsSize,
            fontWeight = FontWeight.ExtraBold,
        )
    }
}
