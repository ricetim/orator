package com.orator.core.designsystem.components

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil3.compose.AsyncImagePainter
import coil3.compose.LocalPlatformContext
import coil3.compose.rememberAsyncImagePainter
import coil3.compose.rememberConstraintsSizeResolver
import coil3.request.ImageRequest

/**
 * Artwork from any source Coil understands (File, content:// URI, https URL), with the
 * initials-on-gradient fallback while loading / on error / when [model] is null.
 *
 * Built on [rememberAsyncImagePainter] rather than SubcomposeAsyncImage on purpose: this is the
 * single artwork funnel for every cover grid and list row in the app, and Coil's own docs say to
 * avoid subcomposition in lazy layouts.
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
        return
    }

    // rememberAsyncImagePainter does NOT resolve display size on its own. Without this it decodes
    // at the image's original dimensions, which for a wall of book covers would cost more than the
    // subcomposition it replaces.
    val sizeResolver = rememberConstraintsSizeResolver()
    val painter = rememberAsyncImagePainter(
        model = ImageRequest.Builder(LocalPlatformContext.current)
            .data(model)
            .size(sizeResolver)
            .build(),
        contentScale = ContentScale.Crop,
    )
    val state by painter.state.collectAsState()

    Box(shaped) {
        // Underlay rather than a swap: the loaded image is cropped to fill and covers this
        // completely, so nothing has to re-lay-out when the load lands.
        if (state !is AsyncImagePainter.State.Success) {
            FallbackArt(title, Modifier.fillMaxSize(), initialsSize)
        }
        Image(
            painter = painter,
            contentDescription = title,
            contentScale = ContentScale.Crop,
            modifier = Modifier.fillMaxSize().then(sizeResolver),
        )
    }
}

@Composable
private fun FallbackArt(title: String, modifier: Modifier, initialsSize: TextUnit) {
    val (start, end) = ArtworkFallback.gradientFor(title)
    Box(
        // The initials are decorative: the real label is the caption beside this artwork, and while
        // the fallback sits under a loading image both would otherwise be announced.
        modifier = modifier
            .clearAndSetSemantics {}
            .background(Brush.linearGradient(listOf(start, end))),
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
