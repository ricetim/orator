package com.orator.feature.audiobookshelf.data

/** Synchronous secure key/value persistence; the encrypted impl is the production binding. */
interface SecureStringStore {
    fun get(key: String): String?
    fun put(key: String, value: String)
    fun clear()
}
