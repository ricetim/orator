package com.orator.core.designsystem.theme

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

/**
 * Single source of truth for the app's visual language. Every color and key dimension in
 * the UI reads from here — tweak this file, restyle the app.
 *
 * Palette: **Solarized Dark** (user decision 2026-06-12, superseding the mockups' OLED
 * true-black scheme; layout still follows ui-mockups/candidate-j.html). Base colors are
 * Ethan Schoonover's; a few in-between tones (borders, tracks, player tints) are derived
 * because Solarized defines no chrome colors.
 */
object OnyxTokens {
    // Surfaces (base03 ground, base02 highlights)
    val Background = Color(0xFF002B36)      // base03
    val Surface = Color(0xFF073642)         // base02
    val SurfaceBorder = Color(0xFF0D4654)   // derived: between base02 and base01
    val NavBackground = Color(0xFF00212B)   // derived: darker base03 (gutter tone)
    val Divider = Color(0xFF06313C)         // derived: hairline on base03
    val BarTrack = Color(0xFF0A3E4B)        // derived: track visible on both bg + surface
    val ChipBackground = Color(0xFF00212B)
    val ChipBorder = Color(0xFF0D4654)

    // Accent (solarized cyan)
    val Accent = Color(0xFF2AA198)          // cyan
    val AccentBright = Color(0xFF45C8BD)    // derived: lightened cyan (chapter bar, highlights)
    val OnAccent = Color(0xFF002B36)        // base03 on cyan

    // Text (solarized base scale)
    val Text = Color(0xFFEEE8D5)            // base2: titles / primary
    val TextDim = Color(0xFF93A1A1)         // base1: secondary
    val TextFaint = Color(0xFF657B83)       // base00: labels, hints

    // Swipe action backgrounds
    val SwipeDelete = Color(0xFFDC322F)     // red

    // Player backdrop tints (radial: tint at top → Background)
    val PodcastTint = Color(0xFF0A4540)     // derived: dark cyan-green
    val BookTint = Color(0xFF46280D)        // derived: dark orange (from solarized orange)

    // Dimensions
    val MiniPlayerHeight = 58.dp
    val NavBarHeight = 64.dp
    val PlayerCoverSize = 300.dp

    /** Bottom content padding for scrollables that sit under the mini player + nav overlay. */
    val OverlayBottomPadding = 140.dp
}
