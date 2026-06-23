package com.orator.feature.audiobookshelf.data

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

private val Context.absDataStore by preferencesDataStore(name = "audiobookshelf")
private val KEY_DOWNLOAD_TREE = stringPreferencesKey("download_tree_uri")

/** Remembers the SAF folder the user granted for ABS downloads. */
@Singleton
class AbsPrefs @Inject constructor(@ApplicationContext private val context: Context) {
    val downloadTreeUri: Flow<String?> = context.absDataStore.data.map { it[KEY_DOWNLOAD_TREE] }

    suspend fun setDownloadTreeUri(uri: String) {
        context.absDataStore.edit { it[KEY_DOWNLOAD_TREE] = uri }
    }

    suspend fun downloadTreeUriNow(): String? = context.absDataStore.data.first()[KEY_DOWNLOAD_TREE]
}
