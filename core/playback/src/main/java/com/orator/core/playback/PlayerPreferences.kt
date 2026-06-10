package com.orator.core.playback

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.orator.core.model.MediaType
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

/** Distinguishes the player-policy DataStore from any other DataStore<Preferences> binding. */
@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class PlayerDataStore

private val Context.playerDataStore by preferencesDataStore(name = "player")

@Module
@InstallIn(SingletonComponent::class)
object PlayerDataStoreModule {
    @Provides
    @Singleton
    @PlayerDataStore
    fun providePlayerDataStore(@ApplicationContext context: Context): DataStore<Preferences> =
        context.playerDataStore
}

private val KEY_GLOBAL_SPEED = floatPreferencesKey("global_speed")
private val KEY_SILENCE_TRIM = booleanPreferencesKey("silence_trim")
private val KEY_BOOST_MB = intPreferencesKey("boost_mb")
private val KEY_SLEEP_MINUTES = intPreferencesKey("default_sleep_minutes")
private fun speedKey(type: MediaType) = floatPreferencesKey("speed_${type.name}")
private fun rewindKey(type: MediaType) = booleanPreferencesKey("rewind_${type.name}")

/** Snapshot of every player-policy preference. */
data class PlayerPrefs(
    val globalSpeed: Float = SpeedResolver.DEFAULT_SPEED,
    val perTypeSpeed: Map<MediaType, Float> = emptyMap(),
    val silenceTrim: Boolean = false,
    val boostMb: Int = 0,
    val smartRewind: Map<MediaType, Boolean> = MediaType.entries.associateWith { true },
    val defaultSleepMinutes: Int = 30,
) {
    fun toSpeedPreferences() = SpeedPreferences(global = globalSpeed, perType = perTypeSpeed)
}

/**
 * Typed DataStore wrapper for player-policy settings (pattern: AudiobooksPrefs, but the
 * store itself is constructor-injected so tests can use a fresh, isolated instance).
 */
@Singleton
class PlayerPreferences @Inject constructor(
    @PlayerDataStore private val dataStore: DataStore<Preferences>,
) {
    val flow: Flow<PlayerPrefs> = dataStore.data.map { p ->
        PlayerPrefs(
            globalSpeed = p[KEY_GLOBAL_SPEED] ?: SpeedResolver.DEFAULT_SPEED,
            perTypeSpeed = MediaType.entries
                .mapNotNull { t -> p[speedKey(t)]?.let { t to it } }
                .toMap(),
            silenceTrim = p[KEY_SILENCE_TRIM] ?: false,
            boostMb = p[KEY_BOOST_MB] ?: 0,
            smartRewind = MediaType.entries.associateWith { t -> p[rewindKey(t)] ?: true },
            defaultSleepMinutes = p[KEY_SLEEP_MINUTES] ?: 30,
        )
    }

    suspend fun setGlobalSpeed(speed: Float) {
        dataStore.edit { it[KEY_GLOBAL_SPEED] = speed }
    }

    suspend fun setTypeSpeed(type: MediaType, speed: Float?) {
        dataStore.edit {
            if (speed == null) it.remove(speedKey(type)) else it[speedKey(type)] = speed
        }
    }

    suspend fun setSilenceTrim(enabled: Boolean) {
        dataStore.edit { it[KEY_SILENCE_TRIM] = enabled }
    }

    suspend fun setBoostMb(mb: Int) {
        dataStore.edit { it[KEY_BOOST_MB] = mb }
    }

    suspend fun setSmartRewind(type: MediaType, enabled: Boolean) {
        dataStore.edit { it[rewindKey(type)] = enabled }
    }

    suspend fun setDefaultSleepMinutes(minutes: Int) {
        dataStore.edit { it[KEY_SLEEP_MINUTES] = minutes }
    }
}
