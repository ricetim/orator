package com.orator.feature.podcasts.data.search

import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import okhttp3.OkHttpClient
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object SearchModule {

    @Provides
    @Singleton
    fun provideCompositeSearchProvider(client: OkHttpClient): CompositeSearchProvider =
        CompositeSearchProvider(
            primary = PodcastIndexSearchProvider(client),
            fallback = ItunesSearchProvider(client),
        )
}
