package com.orator.feature.audiobooks.data

import android.content.Context
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.orator.feature.audiobooks.BookSortMode
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.map
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton

private val Context.audiobooksDataStore by preferencesDataStore(name = "audiobooks")
private val KEY_TREE_URI = stringPreferencesKey("tree_uri")
private val KEY_SORT_MODE = stringPreferencesKey("book_sort_mode")

/** Remembers which folder the user granted us, and how they like the library ordered. */
@Singleton
class AudiobooksPrefs @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    // DataStore surfaces read failures (IOException, CorruptionException) to collectors, and an
    // uncaught one in a viewModelScope collector crashes the app. Degrade to defaults instead.
    private val data: Flow<Preferences> = context.audiobooksDataStore.data
        .catch { if (it is IOException) emit(emptyPreferences()) else throw it }

    val treeUri: Flow<String?> = data.map { it[KEY_TREE_URI] }

    suspend fun setTreeUri(uri: String) {
        context.audiobooksDataStore.edit { it[KEY_TREE_URI] = uri }
    }

    /** Unrecognised stored values (e.g. a mode removed in a later version) fall back to RECENT. */
    val sortMode: Flow<BookSortMode> = data.map { prefs ->
        val stored = prefs[KEY_SORT_MODE]
        BookSortMode.entries.firstOrNull { it.name == stored } ?: BookSortMode.RECENT
    }

    suspend fun setSortMode(mode: BookSortMode) {
        context.audiobooksDataStore.edit { it[KEY_SORT_MODE] = mode.name }
    }
}
