package com.akouo.feature.audiobooks

import com.akouo.core.navigation.FeatureEntry
import com.akouo.core.playback.PlaybackPositionListener
import com.akouo.feature.audiobooks.data.AudiobookMetadataExtractor
import com.akouo.feature.audiobooks.data.AudiobookPositionListener
import com.akouo.feature.audiobooks.data.ContentResolverM4bChapterSource
import com.akouo.feature.audiobooks.data.M4bChapterSource
import com.akouo.feature.audiobooks.data.MmrMetadataExtractor
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
    fun bindMetadataExtractor(impl: MmrMetadataExtractor): AudiobookMetadataExtractor

    @Binds
    fun bindChapterSource(impl: ContentResolverM4bChapterSource): M4bChapterSource
}
