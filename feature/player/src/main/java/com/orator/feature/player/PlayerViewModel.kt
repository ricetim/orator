package com.orator.feature.player

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.orator.core.playback.PlaybackConnection
import com.orator.core.playback.PlaybackUiState
import com.orator.core.playback.PlayerPreferences
import com.orator.core.playback.PlayerPrefs
import com.orator.core.playback.SleepTimer
import com.orator.core.playback.SleepTimerState
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class PlayerViewModel @Inject constructor(
    private val playbackConnection: PlaybackConnection,
    private val sleepTimer: SleepTimer,
    private val playerPreferences: PlayerPreferences,
) : ViewModel() {

    val uiState: StateFlow<PlaybackUiState> = playbackConnection.state

    val sleepState: StateFlow<SleepTimerState> = sleepTimer.state

    val prefs: StateFlow<PlayerPrefs> = playerPreferences.flow
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), PlayerPrefs())

    fun onPlayPauseClick() = playbackConnection.playPause()

    fun onSeekBy(deltaMs: Long) = playbackConnection.seekBy(deltaMs)

    fun onSeekTo(positionMs: Long) = playbackConnection.seekWithinCurrent(positionMs)

    /** Steps the per-item override relative to the currently effective speed. */
    fun onSpeedStep(delta: Float) {
        val next = (uiState.value.speed + delta).coerceIn(0.5f, 3.0f)
        playbackConnection.setSpeedOverride((next * 100).toInt() / 100f)
    }

    fun onSpeedReset() = playbackConnection.setSpeedOverride(null)

    fun onSleepDuration(minutes: Int) = sleepTimer.armDuration(minutes)

    fun onSleepBoundary() = sleepTimer.armBoundary()

    fun onSleepCancel() = sleepTimer.cancel()

    fun onDefaultSleep() {
        viewModelScope.launch {
            sleepTimer.armDuration(playerPreferences.flow.first().defaultSleepMinutes)
        }
    }

    fun onTrimToggle(on: Boolean) {
        viewModelScope.launch { playerPreferences.setSilenceTrim(on) }
    }

    fun onBoostStep(deltaMb: Int) {
        viewModelScope.launch {
            playerPreferences.setBoostMb((prefs.value.boostMb + deltaMb).coerceIn(0, 1500))
        }
    }
}
