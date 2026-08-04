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
import androidx.compose.runtime.remember
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
 *
 * Treated as decorative for accessibility — every caller pairs it with a caption carrying the
 * title, and labelling it too would announce the title twice.
 *
 * [modifier] MUST carry a bounded size. Size resolution is the whole point of this component, and
 * an unbounded axis resolves to the image's original dimensions.
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

    // rememberAsyncImagePainter does NOT resolve display size on its own. Without an explicit
    // resolver Coil falls back to Size.ORIGINAL and decodes covers at full resolution, which would
    // cost more than the subcomposition this replaces.
    val context = LocalPlatformContext.current
    val sizeResolver = rememberConstraintsSizeResolver()
    val request = remember(context, model, sizeResolver) {
        ImageRequest.Builder(context).data(model).size(sizeResolver).build()
    }
    val painter = rememberAsyncImagePainter(
        model = request,
        // Load-bearing beyond draw-time cropping: this also sets Scale.FILL on the decode. Drop it
        // and the decoder returns a FIT-sized bitmap that Crop then upscales.
        contentScale = ContentScale.Crop,
    )

    Box(shaped) {
        ArtworkUnderlay(painter, title, initialsSize)
        Image(
            painter = painter,
            contentDescription = null,
            contentScale = ContentScale.Crop,
            modifier = Modifier.fillMaxSize().then(sizeResolver),
        )
    }
}

/**
 * The fallback, drawn under the image until it loads. Reads the load state in its own scope so a
 * finished load invalidates only this, leaving the image's measurement untouched.
 */
@Composable
private fun ArtworkUnderlay(painter: AsyncImagePainter, title: String, initialsSize: TextUnit) {
    val state by painter.state.collectAsState()
    if (state !is AsyncImagePainter.State.Success) {
        FallbackArt(title, Modifier.fillMaxSize(), initialsSize)
    }
}

@Composable
private fun FallbackArt(title: String, modifier: Modifier, initialsSize: TextUnit) {
    val (start, end) = ArtworkFallback.gradientFor(title)
    Box(
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
