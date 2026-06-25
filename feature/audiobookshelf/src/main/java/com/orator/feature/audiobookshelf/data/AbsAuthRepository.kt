package com.orator.feature.audiobookshelf.data

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Drives the connect flow: exchange username/password for a token, persist it (never the password),
 * trigger the initial catalog sync, and publish [state]. Function seams ([loginFn], [onConnected])
 * keep it unit-testable without AbsApi/AbsRepository; the Hilt provider adapts the real ones.
 */
class AbsAuthRepository(
    private val loginFn: suspend (baseUrl: String, user: String, pass: String) -> AbsUser,
    private val store: AbsCredentialStore,
    private val onConnected: suspend () -> Unit,
) {
    private val _state = MutableStateFlow<AbsConnectionState>(
        store.current()?.let { AbsConnectionState.Connected(it.config) } ?: AbsConnectionState.Disconnected,
    )
    val state: StateFlow<AbsConnectionState> = _state.asStateFlow()

    suspend fun login(baseUrl: String, username: String, password: String) {
        _state.value = AbsConnectionState.Connecting
        runCatching {
            val user = loginFn(baseUrl, username, password)
            val cfg = AbsServerConfig(
                serverId = AbsUrl.serverId(baseUrl),
                baseUrl = baseUrl.trim().trimEnd('/'),
                username = username,
                token = user.token,
            )
            store.save(cfg)
            onConnected()
            cfg
        }.onSuccess { _state.value = AbsConnectionState.Connected(it) }
            .onFailure { _state.value = AbsConnectionState.Error(it.message ?: "Login failed") }
    }
}
