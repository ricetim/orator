package com.orator.core.playback

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject
import javax.inject.Singleton

sealed interface SleepTimerState {
    data object Off : SleepTimerState

    /** Pause when the wall clock reaches [endsAtMs]. */
    data class Duration(val endsAtMs: Long) : SleepTimerState

    /** Pause at the next chapter boundary (single-file books) or item transition. */
    data object EndOfBoundary : SleepTimerState
}

/**
 * Shared sleep-timer command holder. The UI arms/cancels; PlaybackService observes [state]
 * and does the pausing. A plain singleton instead of Media3 custom session commands because
 * app and service share one process (see the Phase 3 plan's Orientation section).
 */
@Singleton
class SleepTimer @Inject constructor() {

    private val _state = MutableStateFlow<SleepTimerState>(SleepTimerState.Off)
    val state: StateFlow<SleepTimerState> = _state.asStateFlow()

    fun armDuration(minutes: Int, nowMs: Long = System.currentTimeMillis()) {
        _state.value = SleepTimerState.Duration(endsAtMs = nowMs + minutes * 60_000L)
    }

    fun armBoundary() {
        _state.value = SleepTimerState.EndOfBoundary
    }

    fun cancel() {
        _state.value = SleepTimerState.Off
    }

    companion object {
        /** First boundary strictly after [positionMs], or null (≙ fall back to item transition). */
        fun nextBoundary(boundariesMs: List<Long>, positionMs: Long): Long? =
            boundariesMs.sorted().firstOrNull { it > positionMs }
    }
}
