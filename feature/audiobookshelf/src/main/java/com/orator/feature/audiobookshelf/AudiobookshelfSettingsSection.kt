package com.orator.feature.audiobookshelf

import android.content.Intent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.orator.core.designsystem.components.SettingsRow
import com.orator.core.designsystem.contract.SettingsSection
import com.orator.feature.audiobookshelf.data.AbsAuthRepository
import com.orator.feature.audiobookshelf.data.AbsConnectionState
import com.orator.feature.audiobookshelf.data.AbsPrefs
import com.orator.feature.audiobookshelf.data.AbsRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

/** The Audiobookshelf block on the Settings screen: connect/refresh/logout + download folder. */
class AudiobookshelfSettingsSection @Inject constructor() : SettingsSection {

    override val order = 30
    override val title = "Audiobookshelf"

    @Composable
    override fun Content() {
        val vm: AudiobookshelfSettingsViewModel = hiltViewModel()
        val state by vm.state.collectAsStateWithLifecycle()
        val hasFolder by vm.hasDownloadFolder.collectAsStateWithLifecycle()
        val context = LocalContext.current
        var showConnect by remember { mutableStateOf(false) }

        val pickFolder = rememberLauncherForActivityResult(
            ActivityResultContracts.OpenDocumentTree(),
        ) { uri ->
            if (uri != null) {
                context.contentResolver.takePersistableUriPermission(
                    uri,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION,
                )
                vm.onDownloadFolderPicked(uri.toString())
            }
        }

        when (val s = state) {
            is AbsConnectionState.Connected -> {
                SettingsRow(glyph = "🟢", label = "Server", value = s.config.baseUrl)
                SettingsRow(glyph = "↻", label = "Refresh library", onClick = vm::onRefresh)
                SettingsRow(
                    glyph = "📁",
                    label = "Download folder",
                    value = if (hasFolder) "set" else "not set",
                    onClick = { pickFolder.launch(null) },
                )
                SettingsRow(glyph = "⏏", label = "Log out", onClick = vm::onLogout)
            }
            AbsConnectionState.Connecting -> SettingsRow(glyph = "⏳", label = "Connecting…")
            is AbsConnectionState.Error -> SettingsRow(
                glyph = "⚠",
                label = "Connect to server",
                value = s.message,
                onClick = { showConnect = true },
            )
            AbsConnectionState.Disconnected -> SettingsRow(
                glyph = "🖧",
                label = "Connect to server",
                value = "not connected",
                onClick = { showConnect = true },
            )
        }

        if (showConnect) {
            ConnectDialog(
                onDismiss = { showConnect = false },
                onConnect = { url, user, pass ->
                    vm.onConnect(url, user, pass)
                    showConnect = false
                },
            )
        }
    }
}

@Composable
private fun ConnectDialog(onDismiss: () -> Unit, onConnect: (String, String, String) -> Unit) {
    var url by remember { mutableStateOf("") }
    var user by remember { mutableStateOf("") }
    var pass by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Connect to audiobookshelf") },
        text = {
            Column {
                OutlinedTextField(
                    value = url, onValueChange = { url = it },
                    label = { Text("Server URL (https://…)") }, singleLine = true,
                )
                OutlinedTextField(
                    value = user, onValueChange = { user = it },
                    label = { Text("Username") }, singleLine = true,
                )
                OutlinedTextField(
                    value = pass, onValueChange = { pass = it },
                    label = { Text("Password") }, singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = { onConnect(url.trim(), user.trim(), pass) },
                enabled = url.isNotBlank() && user.isNotBlank() && pass.isNotBlank(),
            ) { Text("Connect") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@HiltViewModel
class AudiobookshelfSettingsViewModel @Inject constructor(
    private val authRepository: AbsAuthRepository,
    private val repository: AbsRepository,
    private val prefs: AbsPrefs,
) : ViewModel() {

    val state: StateFlow<AbsConnectionState> = authRepository.state

    val hasDownloadFolder: StateFlow<Boolean> = prefs.downloadTreeUri
        .map { it != null }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), false)

    fun onConnect(url: String, user: String, pass: String) {
        viewModelScope.launch { authRepository.login(url, user, pass) }
    }

    fun onRefresh() { viewModelScope.launch { repository.sync() } }
    fun onLogout() { viewModelScope.launch { repository.logout() } }
    fun onDownloadFolderPicked(uri: String) {
        viewModelScope.launch { prefs.setDownloadTreeUri(uri) }
    }
}
