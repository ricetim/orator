package com.orator.core.designsystem.contract

import androidx.compose.runtime.Composable

/**
 * A feature-owned block of rows on the Settings screen, collected via Hilt @IntoSet —
 * same pluggability idea as FeatureEntry. Lets feature:podcasts own "Import OPML" without
 * feature:settings depending on it.
 */
interface SettingsSection {
    /** Sections render in ascending order. Library sections: 10–19; leave room. */
    val order: Int

    /** Uppercase section header ("Podcasts", "Audiobooks"). */
    val title: String

    @Composable
    fun Content()
}
