package com.orator.feature.player.pages

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.orator.core.designsystem.components.ArtworkImage
import com.orator.core.designsystem.theme.OnyxTokens

/** Pager page 0: the big cover (mockup .part — 300dp, 20dp radius, deep shadow). */
@Composable
fun CoverPage(artworkModel: Any?, title: String) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
        Box(
            Modifier
                .padding(top = 8.dp)
                .size(OnyxTokens.PlayerCoverSize)
                .shadow(24.dp, RoundedCornerShape(20.dp)),
        ) {
            ArtworkImage(
                model = artworkModel,
                title = title,
                modifier = Modifier.fillMaxSize(),
                cornerRadius = 20.dp,
                initialsSize = 74.sp,
            )
        }
    }
}
