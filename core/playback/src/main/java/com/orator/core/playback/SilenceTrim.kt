package com.orator.core.playback

import android.content.Context
import androidx.annotation.OptIn
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.RenderersFactory
import androidx.media3.exoplayer.audio.AudioSink
import androidx.media3.exoplayer.audio.DefaultAudioSink
import androidx.media3.exoplayer.audio.SilenceSkippingAudioProcessor
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Owns the silence-skipping audio processor. The processor must be baked into the player's
 * audio sink at build time (renderersFactory), but its enabled flag can be flipped at runtime —
 * PlaybackService binds it to the silenceTrim preference.
 */
@OptIn(UnstableApi::class)
@Singleton
class SilenceTrim @Inject constructor() {

    private val processor = SilenceSkippingAudioProcessor()

    fun renderersFactory(context: Context): RenderersFactory =
        object : DefaultRenderersFactory(context) {
            override fun buildAudioSink(
                context: Context,
                enableFloatOutput: Boolean,
                enableAudioTrackPlaybackParams: Boolean,
            ): AudioSink = DefaultAudioSink.Builder(context)
                .setAudioProcessors(arrayOf(processor))
                .build()
        }

    fun setEnabled(enabled: Boolean) = processor.setEnabled(enabled)
}
