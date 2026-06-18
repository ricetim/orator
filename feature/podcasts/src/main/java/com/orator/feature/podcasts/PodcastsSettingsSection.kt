package com.orator.feature.podcasts

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.orator.core.designsystem.components.SettingsRow
import com.orator.core.designsystem.contract.SettingsSection
import com.orator.core.designsystem.theme.OnyxTokens
import com.orator.feature.podcasts.data.PodcastRepository
import com.orator.feature.podcasts.data.PodcastsFolderStore
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.inject.Inject

/** The podcasts block on the Settings screen: OPML import + storage folder. */
class PodcastsSettingsSection @Inject constructor() : SettingsSection {

    override val order = 10
    override val title = "Podcasts"

    @Composable
    override fun Content() {
        val viewModel: PodcastsSettingsViewModel = hiltViewModel()
        val hasFolder by viewModel.hasFolder.collectAsStateWithLifecycle()
        val lastResult by viewModel.lastResult.collectAsStateWithLifecycle()
        val context = LocalContext.current

        val pickOpml = rememberLauncherForActivityResult(
            ActivityResultContracts.OpenDocument(),
        ) { uri -> if (uri != null) viewModel.onImportOpml(uri) }

        val pickFolder = rememberLauncherForActivityResult(
            ActivityResultContracts.OpenDocumentTree(),
        ) { uri ->
            if (uri != null) {
                context.contentResolver.takePersistableUriPermission(
                    uri,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION,
                )
                viewModel.onFolderPicked(uri.toString())
            }
        }

        SettingsRow(
            glyph = "⇪",
            label = "Import OPML",
            onClick = { pickOpml.launch(arrayOf("text/xml", "application/xml", "text/x-opml", "*/*")) },
        )
        SettingsRow(
            glyph = "📁",
            label = "Podcast storage folder",
            value = if (hasFolder) "set" else "not set",
            onClick = { pickFolder.launch(null) },
        )
        lastResult?.let {
            Text(
                it,
                color = OnyxTokens.TextFaint,
                fontSize = 11.sp,
                modifier = Modifier.padding(horizontal = 18.dp),
            )
        }
    }
}

@HiltViewModel
class PodcastsSettingsViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val repository: PodcastRepository,
    private val folderStore: PodcastsFolderStore,
) : ViewModel() {

    val hasFolder: StateFlow<Boolean> = folderStore.treeUri.map { it != null }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), false)

    /** One-shot result line ("Imported 42, 1 failed"); cleared on the next action. */
    private val _lastResult = MutableStateFlow<String?>(null)
    val lastResult: StateFlow<String?> = _lastResult.asStateFlow()

    fun onFolderPicked(treeUri: String) {
        viewModelScope.launch { folderStore.setTreeUri(treeUri) }
    }

    fun onImportOpml(uri: Uri) {
        viewModelScope.launch {
            _lastResult.value = null
            val xml = runCatching {
                withContext(Dispatchers.IO) {
                    context.contentResolver.openInputStream(uri)?.use {
                        it.readBytes().decodeToString()
                    }
                }
            }.getOrNull()
            if (xml == null) {
                _lastResult.value = "Could not read OPML"
                return@launch
            }
            val summary = repository.importOpml(xml)
            _lastResult.value = "Imported ${summary.refreshed}, ${summary.failed} failed"
        }
    }
}
