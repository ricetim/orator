package com.orator.feature.audiobookshelf.data

import java.util.concurrent.atomic.AtomicReference
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Holds the current ABS session in an AtomicReference (lock-free, read synchronously by the auth
 * interceptor on OkHttp I/O threads) and mirrors it into a SecureStringStore so it survives restart.
 * Lazily hydrates from the secure store on first access — no eager init needed.
 */
@Singleton
class AbsCredentialStore @Inject constructor(
    private val secure: SecureStringStore,
) {
    private val ref = AtomicReference<AbsSession?>(null)
    @Volatile private var hydrated = false

    fun current(): AbsSession? {
        if (!hydrated) hydrate()
        return ref.get()
    }

    @Synchronized private fun hydrate() {
        if (hydrated) return
        val base = secure.get(KEY_BASE)
        val user = secure.get(KEY_USER)
        val token = secure.get(KEY_TOKEN)
        val serverId = secure.get(KEY_SERVER_ID)
        if (base != null && user != null && token != null && serverId != null) {
            ref.set(AbsSession.of(AbsServerConfig(serverId, base, user, token)))
        }
        hydrated = true
    }

    fun save(config: AbsServerConfig) {
        secure.put(KEY_SERVER_ID, config.serverId)
        secure.put(KEY_BASE, config.baseUrl)
        secure.put(KEY_USER, config.username)
        secure.put(KEY_TOKEN, config.token)
        ref.set(AbsSession.of(config))
        hydrated = true
    }

    fun clear() {
        secure.clear()
        ref.set(null)
        hydrated = true
    }

    private companion object {
        const val KEY_SERVER_ID = "server_id"
        const val KEY_BASE = "base_url"
        const val KEY_USER = "username"
        const val KEY_TOKEN = "token"
    }
}
