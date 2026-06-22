package com.orator.feature.podcasts.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Qualifier
import javax.inject.Singleton

@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class RefreshDataStore

private val Context.refreshDataStore by preferencesDataStore(name = "refresh")
private val KEY_INTERVAL = intPreferencesKey("interval_minutes")

/** Default background-refresh cadence when the user hasn't chosen one (6h). */
const val DEFAULT_REFRESH_INTERVAL_MINUTES = 360

@Module
@InstallIn(SingletonComponent::class)
object RefreshDataStoreModule {
    @Provides
    @Singleton
    @RefreshDataStore
    fun provide(@ApplicationContext context: Context): DataStore<Preferences> = context.refreshDataStore
}

/** Background feed-refresh interval in minutes (0 = Off). Survives process death. */
@Singleton
class RefreshPreferences @Inject constructor(
    @RefreshDataStore private val store: DataStore<Preferences>,
) {
    val intervalMinutes: Flow<Int> =
        store.data.map { it[KEY_INTERVAL] ?: DEFAULT_REFRESH_INTERVAL_MINUTES }

    suspend fun setIntervalMinutes(value: Int) {
        store.edit { it[KEY_INTERVAL] = value }
    }
}
