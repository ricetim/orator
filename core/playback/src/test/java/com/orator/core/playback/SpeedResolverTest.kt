package com.orator.core.playback

import com.orator.core.model.MediaType
import org.junit.Assert.assertEquals
import org.junit.Test

class SpeedResolverTest {

    @Test
    fun itemOverride_takesPrecedenceOverEverything() {
        val prefs = SpeedPreferences(global = 1.0f, perType = mapOf(MediaType.PODCAST to 1.5f))
        val result = SpeedResolver.resolve(prefs, MediaType.PODCAST, itemOverride = 2.0f)
        assertEquals(2.0f, result, 0.0f)
    }

    @Test
    fun perTypeDefault_usedWhenNoItemOverride() {
        val prefs = SpeedPreferences(global = 1.0f, perType = mapOf(MediaType.AUDIOBOOK to 1.25f))
        val result = SpeedResolver.resolve(prefs, MediaType.AUDIOBOOK, itemOverride = null)
        assertEquals(1.25f, result, 0.0f)
    }

    @Test
    fun globalDefault_usedWhenNoTypeOrItemValue() {
        val prefs = SpeedPreferences(global = 1.1f, perType = emptyMap())
        val result = SpeedResolver.resolve(prefs, MediaType.PODCAST, itemOverride = null)
        assertEquals(1.1f, result, 0.0f)
    }

    @Test
    fun fallsBackToDefaultSpeed_whenPrefsAreEmpty() {
        val prefs = SpeedPreferences()
        val result = SpeedResolver.resolve(prefs, MediaType.PODCAST, itemOverride = null)
        assertEquals(SpeedResolver.DEFAULT_SPEED, result, 0.0f)
    }
}
