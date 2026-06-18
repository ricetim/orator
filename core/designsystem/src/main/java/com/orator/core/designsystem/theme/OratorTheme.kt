package com.orator.core.designsystem.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable

/**
 * App-wide Material 3 theme. Onyx is dark-only by design (OLED true black); there is no
 * light scheme. M3 components pick the palette up from the scheme; custom components read
 * OnyxTokens directly.
 */
@Composable
fun OratorTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = darkColorScheme(
            primary = OnyxTokens.Accent,
            onPrimary = OnyxTokens.OnAccent,
            secondary = OnyxTokens.AccentBright,
            onSecondary = OnyxTokens.OnAccent,
            background = OnyxTokens.Background,
            onBackground = OnyxTokens.Text,
            surface = OnyxTokens.Surface,
            onSurface = OnyxTokens.Text,
            surfaceVariant = OnyxTokens.Surface,
            onSurfaceVariant = OnyxTokens.TextDim,
            surfaceContainer = OnyxTokens.Surface,
            surfaceContainerHigh = OnyxTokens.Surface,
            surfaceContainerHighest = OnyxTokens.Surface,
            surfaceContainerLow = OnyxTokens.NavBackground,
            outline = OnyxTokens.SurfaceBorder,
            outlineVariant = OnyxTokens.Divider,
            error = OnyxTokens.SwipeDelete,
        ),
        content = content,
    )
}
