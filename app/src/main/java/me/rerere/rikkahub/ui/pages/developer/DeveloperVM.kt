package me.rerere.rikkahub.ui.pages.developer

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import me.rerere.rikkahub.data.datastore.Settings
import me.rerere.rikkahub.data.datastore.SettingsStore
import me.rerere.rikkahub.data.ai.AILoggingManager
import me.rerere.rikkahub.utils.UiState
import me.rerere.rikkahub.utils.UpdateChecker
import me.rerere.rikkahub.utils.UpdateInfo
import me.rerere.rikkahub.utils.UpdateSource

class DeveloperVM(
    private val aiLoggingManager: AILoggingManager,
    private val settingsStore: SettingsStore,
    private val context: Context,
    private val updateChecker: UpdateChecker,
) : ViewModel() {
    val logs = aiLoggingManager.getLogs()
    val settings = settingsStore.settingsFlow

    private val _updateState = kotlinx.coroutines.flow.MutableStateFlow<UiState<UpdateInfo>?>(null)
    val updateState = _updateState.asStateFlow()

    private val _selectedSource = kotlinx.coroutines.flow.MutableStateFlow(UpdateSource.GITHUB)
    val selectedSource = _selectedSource.asStateFlow()

    fun updateSettings(update: (Settings) -> Settings) {
        viewModelScope.launch {
            settingsStore.update(update)
        }
    }

    // Removed.

    fun selectSource(source: UpdateSource) {
        _selectedSource.value = source
    }

    fun checkForUpdates(source: UpdateSource) {
        viewModelScope.launch {
            updateChecker.checkUpdate(source).collect { state ->
                _updateState.value = state
            }
        }
    }
}