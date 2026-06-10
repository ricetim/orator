package com.orator.core.playback

import android.media.audiofx.LoudnessEnhancer
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Volume boost via the platform LoudnessEnhancer, bound to the player's audio session.
 * Creation and every call can throw on some devices (vendor audiofx bugs) — failures
 * silently disable boost rather than crash playback.
 */
@Singleton
class LoudnessBooster @Inject constructor() {

    private var enhancer: LoudnessEnhancer? = null
    private var gainMb: Int = 0

    fun attach(audioSessionId: Int) {
        release()
        if (audioSessionId == 0) return // AudioManager.ERROR / unset
        enhancer = try {
            LoudnessEnhancer(audioSessionId)
        } catch (_: RuntimeException) {
            null
        }
        applyGain()
    }

    fun setGain(mb: Int) {
        gainMb = mb.coerceIn(0, MAX_GAIN_MB)
        applyGain()
    }

    private fun applyGain() {
        val e = enhancer ?: return
        try {
            e.setTargetGain(gainMb)
            e.enabled = gainMb > 0
        } catch (_: RuntimeException) {
            release()
        }
    }

    fun release() {
        try {
            enhancer?.release()
        } catch (_: RuntimeException) {
            // already dead; nothing to do
        }
        enhancer = null
    }

    companion object {
        /** Clipping safeguard: +15 dB is already a lot; refuse anything higher. */
        const val MAX_GAIN_MB = 1500
    }
}
