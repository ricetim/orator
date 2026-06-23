package com.orator.feature.audiobookshelf.data

import okhttp3.HttpUrl.Companion.toHttpUrl

object AbsUrl {
    /** Stable id = scheme://host[:port], no trailing slash, lowercased host, default port dropped. */
    fun serverId(baseUrl: String): String {
        val u = baseUrl.trim().toHttpUrl()
        val portPart = if (u.port == defaultPort(u.scheme)) "" else ":${u.port}"
        return "${u.scheme}://${u.host}$portPart"
    }

    fun endpoint(baseUrl: String, path: String): String =
        baseUrl.trim().trimEnd('/') + "/" + path.trimStart('/')

    private fun defaultPort(scheme: String) = if (scheme == "https") 443 else 80
}
