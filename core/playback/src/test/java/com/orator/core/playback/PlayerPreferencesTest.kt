package com.orator.core.playback

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import com.orator.core.model.MediaType
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class PlayerPreferencesTest {

    @get:Rule
    val tmp = TemporaryFolder()

    // Fresh store per test: a `by preferencesDataStore` delegate caches one instance statically
    // per process, which would leak state across test methods.
    private val prefs by lazy {
        PlayerPreferences(
            PreferenceDataStoreFactory.create { File(tmp.root, "test.preferences_pb") },
        )
    }

    @Test
    fun `defaults are sane`() = runBlocking {
        val p = prefs.flow.first()
        assertEquals(1.0f, p.globalSpeed)
        assertEquals(emptyMap<MediaType, Float>(), p.perTypeSpeed)
        assertEquals(false, p.silenceTrim)
        assertEquals(0, p.boostMb)
        assertEquals(true, p.smartRewind.getValue(MediaType.AUDIOBOOK))
        assertEquals(true, p.smartRewind.getValue(MediaType.PODCAST))
        assertEquals(30, p.defaultSleepMinutes)
    }

    @Test
    fun `values round-trip`() = runBlocking {
        prefs.setGlobalSpeed(1.5f)
        prefs.setTypeSpeed(MediaType.AUDIOBOOK, 1.25f)
        prefs.setSilenceTrim(true)
        prefs.setBoostMb(600)
        prefs.setSmartRewind(MediaType.PODCAST, false)
        prefs.setDefaultSleepMinutes(45)

        val p = prefs.flow.first()
        assertEquals(1.5f, p.globalSpeed)
        assertEquals(1.25f, p.perTypeSpeed.getValue(MediaType.AUDIOBOOK))
        assertEquals(true, p.silenceTrim)
        assertEquals(600, p.boostMb)
        assertEquals(false, p.smartRewind.getValue(MediaType.PODCAST))
        assertEquals(true, p.smartRewind.getValue(MediaType.AUDIOBOOK))
        assertEquals(45, p.defaultSleepMinutes)
    }

    @Test
    fun `clearing a per-type speed falls back to global`() = runBlocking {
        prefs.setTypeSpeed(MediaType.AUDIOBOOK, 1.25f)
        prefs.setTypeSpeed(MediaType.AUDIOBOOK, null)
        val p = prefs.flow.first()
        assertEquals(null, p.perTypeSpeed[MediaType.AUDIOBOOK])
    }

    @Test
    fun `toSpeedPreferences feeds the existing resolver`() = runBlocking {
        prefs.setGlobalSpeed(2.0f)
        prefs.setTypeSpeed(MediaType.PODCAST, 1.1f)
        val sp = prefs.flow.first().toSpeedPreferences()
        assertEquals(1.1f, SpeedResolver.resolve(sp, MediaType.PODCAST, itemOverride = null))
        assertEquals(2.0f, SpeedResolver.resolve(sp, MediaType.AUDIOBOOK, itemOverride = null))
        assertEquals(0.8f, SpeedResolver.resolve(sp, MediaType.PODCAST, itemOverride = 0.8f))
    }
}
