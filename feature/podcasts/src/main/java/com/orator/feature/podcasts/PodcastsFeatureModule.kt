package com.orator.feature.podcasts

import com.orator.core.designsystem.contract.SettingsSection
import com.orator.core.navigation.FeatureEntry
import com.orator.core.playback.ClipOverrideListener
import com.orator.core.playback.PlaybackPositionListener
import com.orator.core.playback.SpeedOverrideListener
import com.orator.feature.podcasts.data.EpisodeClipListener
import com.orator.feature.podcasts.data.EpisodeSpeedOverrideListener
import com.orator.feature.podcasts.data.PodcastPositionListener
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import dagger.multibindings.IntoSet

@Module
@InstallIn(SingletonComponent::class)
interface PodcastsFeatureModule {

    @Binds
    @IntoSet
    fun bindFeatureEntry(entry: PodcastsFeatureEntry): FeatureEntry

    @Binds
    @IntoSet
    fun bindPositionListener(listener: PodcastPositionListener): PlaybackPositionListener

    @Binds
    @IntoSet
    fun bindSpeedOverrideListener(listener: EpisodeSpeedOverrideListener): SpeedOverrideListener

    @Binds
    @IntoSet
    fun bindClipOverrideListener(listener: EpisodeClipListener): ClipOverrideListener

    @Binds
    @IntoSet
    fun bindSettingsSection(section: PodcastsSettingsSection): SettingsSection
}
