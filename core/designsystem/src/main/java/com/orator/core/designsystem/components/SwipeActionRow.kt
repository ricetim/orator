package com.orator.core.designsystem.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.SwipeToDismissBox
import androidx.compose.material3.SwipeToDismissBoxValue
import androidx.compose.material3.Text
import androidx.compose.material3.rememberSwipeToDismissBoxState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.orator.core.designsystem.theme.OnyxTokens

/**
 * Swipe-to-reveal action row. The row itself never dismisses: crossing the threshold fires
 * [onSwipeLeft] and snaps back (confirmValueChange returns false). Built so queue swipe
 * actions can plug in a right-swipe later (Phase 5).
 */
@Composable
fun SwipeActionRow(
    enabled: Boolean,
    actionLabel: String,
    onSwipeLeft: () -> Unit,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    if (!enabled) {
        Box(modifier) { content() }
        return
    }
    val state = rememberSwipeToDismissBoxState(
        confirmValueChange = { value ->
            if (value == SwipeToDismissBoxValue.EndToStart) onSwipeLeft()
            false // never actually dismiss; snap back
        },
    )
    SwipeToDismissBox(
        state = state,
        modifier = modifier,
        enableDismissFromStartToEnd = false,
        backgroundContent = {
            Box(
                Modifier.fillMaxSize().background(OnyxTokens.SwipeDelete).padding(end = 20.dp),
                contentAlignment = Alignment.CenterEnd,
            ) {
                Text(actionLabel, color = Color.White, fontWeight = FontWeight.Bold, fontSize = 13.sp)
            }
        },
        content = { Box(Modifier.background(OnyxTokens.Background)) { content() } },
    )
}
