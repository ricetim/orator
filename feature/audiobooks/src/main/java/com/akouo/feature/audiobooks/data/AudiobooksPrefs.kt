package com.akouo.feature.audiobooks.data

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

private val Context.audiobooksDataStore by preferencesDataStore(name = "audiobooks")
private val KEY_TREE_URI = stringPreferencesKey("tree_uri")

/** Remembers which folder the user granted us. */
@Singleton
class AudiobooksPrefs @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    val treeUri: Flow<String?> = context.audiobooksDataStore.data.map { it[KEY_TREE_URI] }

    suspend fun setTreeUri(uri: String) {
        context.audiobooksDataStore.edit { it[KEY_TREE_URI] = uri }
    }
}
