package com.orator.feature.podcasts.data

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

private val Context.podcastsDataStore by preferencesDataStore(name = "podcasts")
private val KEY_TREE_URI = stringPreferencesKey("tree_uri")

/** Remembers the SAF base folder the user granted for the podcast cache tree. */
@Singleton
class PodcastsFolderStore @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    val treeUri: Flow<String?> = context.podcastsDataStore.data.map { it[KEY_TREE_URI] }

    suspend fun setTreeUri(uri: String) {
        context.podcastsDataStore.edit { it[KEY_TREE_URI] = uri }
    }
}
