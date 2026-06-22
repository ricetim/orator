package com.orator.feature.podcasts.data

/** The selectable background-refresh intervals (minutes; 0 = Off) and a tap-to-cycle helper. */
object RefreshPresets {
    /** Ordered presets the Settings row cycles through. 15 is WorkManager's periodic floor. */
    val MINUTES = listOf(0, 15, 60, 180, 360, 720, 1440)

    fun label(minutes: Int): String = when (minutes) {
        0 -> "Off"
        15 -> "15m"
        60 -> "1h"
        180 -> "3h"
        360 -> "6h"
        720 -> "12h"
        1440 -> "Daily"
        else -> "${minutes}m"
    }

    /** Next preset in the cycle; wraps daily → Off. An unknown value resets to the first preset. */
    fun next(minutes: Int): Int {
        val i = MINUTES.indexOf(minutes)
        return if (i < 0) MINUTES.first() else MINUTES[(i + 1) % MINUTES.size]
    }
}
