package com.orator.core.playback

import javax.inject.Inject

/**
 * Warm-resume smart rewind: remembers when (and on which item) playback paused; on resume
 * returns how far to seek back. Pure bookkeeping — the service passes clocks and does the
 * actual seek. Cold resumes (process death) use SmartRewind directly from lastPlayedAtMs
 * (feature:audiobooks).
 */
class SmartRewindController @Inject constructor() {

    private var pausedAtMs: Long = 0
    private var pausedMediaId: String? = null

    fun onPaused(mediaId: String?, nowMs: Long) {
        pausedMediaId = mediaId
        pausedAtMs = nowMs
    }

    /** Returns ms to seek back (0 = nothing). Consumes the pending pause either way. */
    fun onResumed(mediaId: String?, nowMs: Long, enabled: Boolean): Long {
        val pending = pausedMediaId
        pausedMediaId = null
        if (!enabled || mediaId == null || mediaId != pending) return 0
        return SmartRewind.rewindMs(nowMs - pausedAtMs)
    }

    /**
     * Call when a NEW queue is loaded (playlist change). A fresh play() chooses its own start
     * position — the cold-resume path already applies SmartRewind there, and chapter/bookmark
     * taps are exact positions — so a pause pending from before the load must not fire on top.
     */
    fun reset() {
        pausedMediaId = null
    }
}
