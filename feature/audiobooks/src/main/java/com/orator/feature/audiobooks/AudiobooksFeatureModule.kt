package com.orator.feature.audiobooks

import com.orator.core.navigation.FeatureEntry
import com.orator.core.playback.PlaybackPositionListener
import com.orator.core.playback.SpeedOverrideListener
import com.orator.feature.audiobooks.data.AudiobookMetadataExtractor
import com.orator.feature.audiobooks.data.AudiobookPositionListener
import com.orator.feature.audiobooks.data.BookSpeedOverrideListener
import com.orator.feature.audiobooks.data.ContentResolverM4bChapterSource
import com.orator.feature.audiobooks.data.M4bChapterSource
import com.orator.feature.audiobooks.data.MmrMetadataExtractor
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import dagger.multibindings.IntoSet

@Module
@InstallIn(SingletonComponent::class)
interface AudiobooksFeatureModule {

    @Binds
    @IntoSet
    fun bindFeatureEntry(entry: AudiobooksFeatureEntry): FeatureEntry

    @Binds
    @IntoSet
    fun bindPositionListener(listener: AudiobookPositionListener): PlaybackPositionListener

    @Binds
    @IntoSet
    fun bindBookSpeedOverrideListener(listener: BookSpeedOverrideListener): SpeedOverrideListener

    @Binds
    fun bindMetadataExtractor(impl: MmrMetadataExtractor): AudiobookMetadataExtractor

    @Binds
    fun bindChapterSource(impl: ContentResolverM4bChapterSource): M4bChapterSource
}
