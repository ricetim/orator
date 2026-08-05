// File-level: the deprecation lands on the imports too, which a class-level @Suppress misses.
@file:Suppress("DEPRECATION")

package com.orator.feature.audiobookshelf.data

import android.content.Context
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject

/**
 * Holds the audiobookshelf token in EncryptedSharedPreferences.
 *
 * androidx.security:security-crypto is deprecated in its entirety as of 1.1.0-beta01, in favour of
 * platform APIs and direct Android Keystore use — 1.1.0 is the final stable release, not a fix. We
 * are on it because it is stable and API-compatible with the alpha we were pinned to; migrating off
 * the library is its own piece of work, and it has to preserve already-stored tokens or every user
 * silently gets logged out of their server.
 */
class EncryptedSecureStringStore @Inject constructor(
    @ApplicationContext context: Context,
) : SecureStringStore {
    private val prefs by lazy {
        val key = MasterKey.Builder(context)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()
        EncryptedSharedPreferences.create(
            context,
            "abs_secure",
            key,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
        )
    }

    override fun get(key: String): String? = prefs.getString(key, null)
    override fun put(key: String, value: String) { prefs.edit().putString(key, value).apply() }
    override fun clear() { prefs.edit().clear().apply() }
}
