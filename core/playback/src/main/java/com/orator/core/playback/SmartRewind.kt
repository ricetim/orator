package com.orator.core.playback

/**
 * How far to seek back when resuming after a pause, so the listener re-anchors in the
 * narrative. Stepped tiers (user-confirmed, Smart AudioBook Player is the reference):
 * the longer you were away, the more context you need back.
 *
 * Pure function — no Android, no clock; callers pass the elapsed pause duration.
 */
object SmartRewind {

    fun rewindMs(pausedForMs: Long): Long = when {
        pausedForMs < 30_000 -> 0
        pausedForMs < 5 * 60_000 -> 5_000
        pausedForMs < 60 * 60_000 -> 15_000
        else -> 30_000
    }
}
