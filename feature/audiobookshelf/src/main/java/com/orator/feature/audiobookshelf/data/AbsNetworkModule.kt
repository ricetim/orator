package com.orator.feature.audiobookshelf.data

import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import kotlinx.serialization.json.Json

@Module
@InstallIn(SingletonComponent::class)
object AbsNetworkModule {
    @Provides fun provideAbsJson(): Json = AbsJson.instance
}
