package com.orator.feature.audiobooks.data

import java.security.MessageDigest

/**
 * A book's identity is a hash of its source document URI: stable across rescans, no
 * coordination needed, and safe to embed in route strings and media ids. Moving the
 * file changes the id (and loses position) — accepted for v1.
 */
object BookIds {
    fun fromUri(uri: String): String =
        MessageDigest.getInstance("SHA-256")
            .digest(uri.toByteArray(Charsets.UTF_8))
            .take(8)
            .joinToString("") { "%02x".format(it) }
}
