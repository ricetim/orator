package com.orator.feature.audiobookshelf.data

sealed interface AbsConnectionState {
    data object Disconnected : AbsConnectionState
    data object Connecting : AbsConnectionState
    data class Connected(val config: AbsServerConfig) : AbsConnectionState
    data class Error(val message: String) : AbsConnectionState
}
