package com.orator.core.designsystem.components

import androidx.compose.ui.graphics.Color

/** Deterministic initials + gradient for items without artwork (mockup placeholder tiles). */
object ArtworkFallback {

    /** Gradient pairs built from the Solarized accent palette (user palette, 2026-06-12). */
    val GRADIENTS: List<Pair<Color, Color>> = listOf(
        Color(0xFF268BD2) to Color(0xFF2AA198), // blue → cyan
        Color(0xFFD33682) to Color(0xFF6C71C4), // magenta → violet
        Color(0xFFB58900) to Color(0xFFCB4B16), // yellow → orange
        Color(0xFF859900) to Color(0xFF2AA198), // green → cyan
        Color(0xFFCB4B16) to Color(0xFFDC322F), // orange → red
        Color(0xFF6C71C4) to Color(0xFF268BD2), // violet → blue
        Color(0xFF2AA198) to Color(0xFF859900), // cyan → green
        Color(0xFF657B83) to Color(0xFF586E75), // base00 → base01 (muted)
        Color(0xFFDC322F) to Color(0xFFD33682), // red → magenta
        Color(0xFF268BD2) to Color(0xFF6C71C4), // blue → violet
    )

    private val ARTICLES = setOf("the", "a", "an")
    private val CONNECTORS = setOf("of", "and", "the", "a", "an", "in", "for", "to")

    /**
     * First character of the first two significant words, uppercased; "?" for blank.
     * A leading article is skipped entirely; later connector words ("of", "and", …) are
     * skipped so "A Princess of Mars" → "PM". The tests are the contract.
     */
    fun initials(title: String): String {
        val words = title.split(' ', '\t')
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .filterIndexed { i, w -> !(i == 0 && w.lowercase() in ARTICLES) }
        val significant = words.filterIndexed { i, w -> i == 0 || w.lowercase() !in CONNECTORS }
        val letters = significant.take(2).mapNotNull { it.firstOrNull()?.uppercaseChar() }
        return if (letters.isEmpty()) "?" else letters.joinToString("")
    }

    fun gradientFor(title: String): Pair<Color, Color> =
        GRADIENTS[Math.floorMod(title.hashCode(), GRADIENTS.size)]
}
