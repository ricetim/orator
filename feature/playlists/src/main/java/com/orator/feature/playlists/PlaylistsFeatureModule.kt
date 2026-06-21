package com.orator.feature.playlists

import com.orator.core.navigation.FeatureEntry
import com.orator.core.playback.NewEpisodeListener
import com.orator.feature.playlists.data.PlaylistAutoInserter
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import dagger.multibindings.IntoSet

/** Contributes PlaylistsFeatureEntry + the auto-insert NewEpisodeListener into app-wide sets. */
@Module
@InstallIn(SingletonComponent::class)
interface PlaylistsFeatureModule {

    @Binds
    @IntoSet
    fun bindFeatureEntry(entry: PlaylistsFeatureEntry): FeatureEntry

    @Binds
    @IntoSet
    fun bindNewEpisodeListener(impl: PlaylistAutoInserter): NewEpisodeListener
}
