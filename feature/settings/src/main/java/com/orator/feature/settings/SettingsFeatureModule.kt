package com.orator.feature.settings

import com.orator.core.navigation.FeatureEntry
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import dagger.multibindings.IntoSet

/** Contributes SettingsFeatureEntry into the app-wide Set<FeatureEntry>. */
@Module
@InstallIn(SingletonComponent::class)
interface SettingsFeatureModule {

    @Binds
    @IntoSet
    fun bindSettingsFeatureEntry(entry: SettingsFeatureEntry): FeatureEntry
}
