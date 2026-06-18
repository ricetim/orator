package com.orator.core.designsystem.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import com.orator.core.designsystem.theme.OnyxTokens

/** Page indicator under the player pager. Mockup .dots. */
@Composable
fun PagerDots(count: Int, selected: Int, onSelect: (Int) -> Unit, modifier: Modifier = Modifier) {
    Row(modifier = modifier.padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(7.dp)) {
        repeat(count) { i ->
            Box(
                Modifier.size(7.dp).clip(CircleShape)
                    .background(if (i == selected) OnyxTokens.Accent else OnyxTokens.ChipBorder)
                    .clickable { onSelect(i) },
            )
        }
    }
}
