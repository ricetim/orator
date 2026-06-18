package com.orator.core.designsystem.text

import java.util.Locale

/** All user-facing time strings, in one tested place. Pure functions, US-locale digits. */
object TimeFormats {

    /** "41:08" or "1:02:22". */
    fun clock(ms: Long): String {
        val totalSec = (ms / 1000).coerceAtLeast(0)
        val h = totalSec / 3600
        val m = (totalSec % 3600) / 60
        val s = totalSec % 60
        return if (h > 0) {
            String.format(Locale.US, "%d:%02d:%02d", h, m, s)
        } else {
            String.format(Locale.US, "%d:%02d", m, s)
        }
    }

    /** "−41:08" — time left in the current item. Uses U+2212 minus like the mockup. */
    fun remaining(positionMs: Long, durationMs: Long): String =
        "−" + clock((durationMs - positionMs).coerceAtLeast(0))

    /** "9h 14m left" / "41m left" / "<1m left" — coarse, for tiles and book bars. */
    fun timeLeft(ms: Long): String {
        val totalMin = ms / 60_000
        val h = totalMin / 60
        val m = totalMin % 60
        return when {
            h > 0 -> "${h}h ${m}m left"
            m > 0 -> "${m}m left"
            else -> "<1m left"
        }
    }

    /** "today" / "yesterday" / "3d ago" / "5w ago" — episode recency. */
    fun relativeDay(utcMs: Long, nowMs: Long = System.currentTimeMillis()): String {
        val days = ((nowMs - utcMs) / 86_400_000L).coerceAtLeast(0)
        return when {
            days < 1 -> "today"
            days < 2 -> "yesterday"
            days < 28 -> "${days}d ago"
            else -> "${days / 7}w ago"
        }
    }

    /** "1×" / "1.2×" / "1.25×". */
    fun speedLabel(speed: Float): String {
        val s = String.format(Locale.US, "%.2f", speed).trimEnd('0').trimEnd('.')
        return "$s×"
    }

    /** Mini player subline: "−41:08 · 1.2× · trim" (remaining omitted when duration unknown). */
    fun miniSubLine(positionMs: Long, durationMs: Long, speed: Float, trimOn: Boolean): String {
        val parts = buildList {
            if (durationMs > 0) add(remaining(positionMs, durationMs))
            add(speedLabel(speed))
            if (trimOn) add("trim")
        }
        return parts.joinToString(" · ")
    }
}
