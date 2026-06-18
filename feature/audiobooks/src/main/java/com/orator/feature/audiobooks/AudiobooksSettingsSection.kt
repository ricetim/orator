package com.orator.feature.audiobooks

import android.content.Intent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.platform.LocalContext
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.orator.core.designsystem.components.SettingsRow
import com.orator.core.designsystem.contract.SettingsSection
import com.orator.feature.audiobooks.data.AudiobookRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

/** The audiobooks block on the Settings screen: library folder + rescan. */
class AudiobooksSettingsSection @Inject constructor() : SettingsSection {

    override val order = 20
    override val title = "Audiobooks"

    @Composable
    override fun Content() {
        val viewModel: AudiobooksSettingsViewModel = hiltViewModel()
        val hasFolder by viewModel.hasFolder.collectAsStateWithLifecycle()
        val context = LocalContext.current

        val pickFolder = rememberLauncherForActivityResult(
            ActivityResultContracts.OpenDocumentTree(),
        ) { uri ->
            if (uri != null) {
                context.contentResolver.takePersistableUriPermission(
                    uri,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION,
                )
                viewModel.onFolderPicked(uri.toString())
            }
        }

        SettingsRow(
            glyph = "📁",
            label = "Audiobook storage folder",
            value = if (hasFolder) "set" else "not set",
            onClick = { pickFolder.launch(null) },
        )
        SettingsRow(glyph = "↻", label = "Rescan library", onClick = viewModel::onRescan)
    }
}

@HiltViewModel
class AudiobooksSettingsViewModel @Inject constructor(
    private val repository: AudiobookRepository,
) : ViewModel() {

    val hasFolder: StateFlow<Boolean> = repository.treeUri
        .map { it != null }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), false)

    fun onFolderPicked(treeUri: String) {
        viewModelScope.launch { repository.setFolderAndRescan(treeUri) }
    }

    fun onRescan() {
        viewModelScope.launch { repository.rescan() }
    }
}
