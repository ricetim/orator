package com.orator.feature.playlists

import com.orator.core.navigation.FeatureEntry
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import dagger.multibindings.IntoSet

/** Contributes PlaylistsFeatureEntry into the app-wide Set<FeatureEntry>. */
@Module
@InstallIn(SingletonComponent::class)
interface PlaylistsFeatureModule {

    @Binds
    @IntoSet
    fun bindFeatureEntry(entry: PlaylistsFeatureEntry): FeatureEntry
}
