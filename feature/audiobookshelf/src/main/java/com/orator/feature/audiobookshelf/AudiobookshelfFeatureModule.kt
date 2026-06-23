package com.orator.feature.audiobookshelf

import com.orator.core.designsystem.contract.SettingsSection
import com.orator.core.model.BookDetailResolver
import com.orator.feature.audiobookshelf.data.AbsAuthInterceptor
import com.orator.feature.audiobookshelf.data.AbsBookDetailResolver
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import dagger.multibindings.IntoSet
import okhttp3.Interceptor

/**
 * Contributes the ABS feature into shared seams via @IntoSet only (never re-declares @Multibinds;
 * those live in feature:audiobooks for the book seams, and in core:network for interceptors).
 */
@Module
@InstallIn(SingletonComponent::class)
interface AudiobookshelfFeatureModule {
    @Binds @IntoSet fun bindAuthInterceptor(i: AbsAuthInterceptor): Interceptor
    @Binds @IntoSet fun bindDetailResolver(r: AbsBookDetailResolver): BookDetailResolver
    @Binds @IntoSet fun bindSettingsSection(s: AudiobookshelfSettingsSection): SettingsSection
}
