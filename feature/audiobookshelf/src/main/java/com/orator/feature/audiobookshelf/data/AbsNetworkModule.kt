package com.orator.feature.audiobookshelf.data

import com.orator.core.database.BookDao
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import kotlinx.serialization.json.Json
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object AbsNetworkModule {
    @Provides fun provideAbsJson(): Json = AbsJson.instance

    @Provides fun provideCatalogSource(api: AbsApi): AbsCatalogSource = api

    @Provides
    @Singleton
    fun provideAbsRepository(
        source: AbsCatalogSource,
        store: AbsCredentialStore,
        bookDao: BookDao,
    ): AbsRepository = AbsRepository(source, store, bookDao)

    @Provides
    @Singleton
    fun provideAuthRepository(
        api: AbsApi,
        store: AbsCredentialStore,
        repo: AbsRepository,
    ): AbsAuthRepository = AbsAuthRepository(
        loginFn = { base, u, p -> api.login(base, u, p) },
        store = store,
        onConnected = { repo.sync() },
    )
}
