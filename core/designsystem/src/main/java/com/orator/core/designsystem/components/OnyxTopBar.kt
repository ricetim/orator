package com.orator.core.designsystem.components

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.orator.core.designsystem.theme.OnyxTokens

/**
 * Screen header: leading icon (☰ or ←), bold title, optional trailing action.
 * Mockup .topbar.
 */
@Composable
fun OnyxTopBar(
    title: String,
    leadingIcon: ImageVector,
    onLeadingClick: () -> Unit,
    modifier: Modifier = Modifier,
    trailing: @Composable () -> Unit = {},
) {
    Row(
        modifier = modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconButton(onClick = onLeadingClick) {
            Icon(leadingIcon, contentDescription = null, tint = OnyxTokens.TextDim)
        }
        Text(
            text = title,
            color = OnyxTokens.Text,
            fontSize = 21.sp,
            fontWeight = FontWeight.ExtraBold,
            modifier = Modifier.weight(1f),
        )
        trailing()
    }
}
