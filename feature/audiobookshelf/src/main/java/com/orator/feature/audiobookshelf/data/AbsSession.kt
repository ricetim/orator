package com.orator.feature.audiobookshelf.data

import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl

/** Config plus its pre-parsed base URL, so the interceptor matches host+port without re-parsing. */
data class AbsSession(val config: AbsServerConfig, val baseUrl: HttpUrl) {
    companion object {
        fun of(config: AbsServerConfig) = AbsSession(config, config.baseUrl.toHttpUrl())
    }
}
