package com.orator.feature.settings

import com.orator.core.designsystem.contract.SettingsSection
import com.orator.core.navigation.FeatureEntry
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import dagger.multibindings.IntoSet
import dagger.multibindings.Multibinds

/** Contributes SettingsFeatureEntry into the app-wide Set<FeatureEntry>. */
@Module
@InstallIn(SingletonComponent::class)
interface SettingsFeatureModule {

    @Binds
    @IntoSet
    fun bindSettingsFeatureEntry(entry: SettingsFeatureEntry): FeatureEntry

    /** Declares the set so it exists (empty) even with no feature sections installed. */
    @Multibinds
    fun settingsSections(): Set<SettingsSection>
}
