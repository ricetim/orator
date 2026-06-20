package com.orator.feature.playlists.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Qualifier
import javax.inject.Singleton

@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class PlaylistDataStore

private val Context.playlistDataStore by preferencesDataStore(name = "playlists")
private val KEY_ACTIVE = longPreferencesKey("active_playlist_id")

@Module
@InstallIn(SingletonComponent::class)
object PlaylistDataStoreModule {
    @Provides
    @Singleton
    @PlaylistDataStore
    fun provide(@ApplicationContext context: Context): DataStore<Preferences> =
        context.playlistDataStore
}

/**
 * Which playlist is currently draining (null = none). An interface so the controller can be
 * tested against a trivial in-memory fake without DataStore/file/scope coordination.
 */
interface ActivePlaylist {
    suspend fun activePlaylistId(): Long?
    suspend fun set(playlistId: Long)
    suspend fun clear()
}

/** DataStore-backed [ActivePlaylist]. Survives process death. */
@Singleton
class ActivePlaylistStore @Inject constructor(
    @PlaylistDataStore private val store: DataStore<Preferences>,
) : ActivePlaylist {
    override suspend fun activePlaylistId(): Long? =
        store.data.map { it[KEY_ACTIVE] }.first()?.takeIf { it >= 0 }

    override suspend fun set(playlistId: Long) {
        store.edit { it[KEY_ACTIVE] = playlistId }
    }

    override suspend fun clear() {
        store.edit { it.remove(KEY_ACTIVE) }
    }
}
