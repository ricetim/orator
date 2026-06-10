package com.orator.feature.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.orator.core.model.MediaType
import com.orator.core.playback.PlayerPreferences
import com.orator.core.playback.PlayerPrefs
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class SettingsViewModel @Inject constructor(
    private val prefs: PlayerPreferences,
) : ViewModel() {

    val state: StateFlow<PlayerPrefs> = prefs.flow
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), PlayerPrefs())

    fun setGlobalSpeed(v: Float) = viewModelScope.launch { prefs.setGlobalSpeed(v) }
    fun setTypeSpeed(t: MediaType, v: Float?) = viewModelScope.launch { prefs.setTypeSpeed(t, v) }
    fun setSilenceTrim(on: Boolean) = viewModelScope.launch { prefs.setSilenceTrim(on) }
    fun setBoostMb(mb: Int) = viewModelScope.launch { prefs.setBoostMb(mb) }
    fun setSmartRewind(t: MediaType, on: Boolean) = viewModelScope.launch { prefs.setSmartRewind(t, on) }
    fun setDefaultSleepMinutes(m: Int) = viewModelScope.launch { prefs.setDefaultSleepMinutes(m) }
}
