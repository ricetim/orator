package com.orator.feature.player

import com.orator.core.navigation.FeatureEntry
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import dagger.multibindings.IntoSet

/**
 * Contributes PlayerFeatureEntry into the app-wide Set<FeatureEntry>.
 * @IntoSet is the mechanism that lets the app collect every feature without knowing them by name.
 */
@Module
@InstallIn(SingletonComponent::class)
interface PlayerFeatureModule {

    @Binds
    @IntoSet
    fun bindPlayerFeatureEntry(entry: PlayerFeatureEntry): FeatureEntry
}
