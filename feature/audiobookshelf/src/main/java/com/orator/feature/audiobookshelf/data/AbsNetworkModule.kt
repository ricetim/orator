package com.orator.feature.audiobookshelf.data

import com.orator.core.database.BookDao
import com.orator.core.database.ChapterDao
import com.orator.core.model.BookDownloadController
import com.orator.core.model.BookOrigin
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import dagger.multibindings.IntoSet
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
        downloader: AbsFileDownloader,
    ): AbsRepository = AbsRepository(source, store, bookDao, deleteFiles = { downloader.deleteFiles(it) })

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

    @Provides
    @Singleton
    fun provideBookDetailResolver(
        api: AbsApi,
        store: AbsCredentialStore,
        bookDao: BookDao,
        chapterDao: ChapterDao,
    ): AbsBookDetailResolver = AbsBookDetailResolver(
        detail = { base, itemId ->
            AbsItemDetailMapper.map(
                api.getItemExpanded(base, itemId, store.current()?.config?.token ?: ""),
                base,
            )
        },
        store = store,
        bookDao = bookDao,
        chapterDao = chapterDao,
    )

    @Provides
    @IntoSet
    fun provideDownloadController(
        manager: AbsDownloadManager,
        downloader: AbsFileDownloader,
    ): BookDownloadController = AbsDownloadController(
        handlesOrigin = BookOrigin.ABS,
        enqueueFn = manager::enqueue,
        cancelFn = manager::cancel,
        removeFn = { manager.cancel(it); downloader.removeDownload(it) },
    )
}
