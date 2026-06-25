package com.orator.feature.audiobooks

import com.orator.core.model.BookDetailResolver
import com.orator.core.model.BookDownloadController
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import dagger.multibindings.Multibinds

/**
 * Sole declarer of the cross-feature book seams' multibindings, so AudiobookPlayRequestFactory /
 * the list can inject them even when feature:audiobookshelf is absent (empty sets). Contributors
 * (the ABS module) add via @IntoSet only — they must NOT re-declare these @Multibinds.
 */
@Module
@InstallIn(SingletonComponent::class)
abstract class AudiobookSeamsModule {
    @Multibinds abstract fun bookDetailResolvers(): Set<BookDetailResolver>
    @Multibinds abstract fun bookDownloadControllers(): Set<BookDownloadController>
}
