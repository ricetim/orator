package com.orator.core.playback

import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import dagger.multibindings.Multibinds

@Module
@InstallIn(SingletonComponent::class)
abstract class PlaybackModule {
    /** Declares the set so it exists (empty) even before any feature contributes a listener. */
    @Multibinds
    abstract fun positionListeners(): Set<PlaybackPositionListener>
}
