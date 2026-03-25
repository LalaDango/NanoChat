package com.example.nanochat.ui.screen.settings

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.example.nanochat.data.repository.NanoChatRepository
import com.example.nanochat.data.repository.SettingsRepository
import com.google.mlkit.genai.common.DownloadStatus
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

data class SettingsUiState(
    val contextWindowSize: Int = SettingsRepository.DEFAULT_CONTEXT_WINDOW_SIZE,
    val systemPrompt: String = SettingsRepository.DEFAULT_SYSTEM_PROMPT,
    val temperature: Float = SettingsRepository.DEFAULT_TEMPERATURE,
    val topK: Int = SettingsRepository.DEFAULT_TOP_K,
    val modelStatus: Int = -1,
    val isDownloading: Boolean = false,
    val downloadProgress: Float = 0f,
    val isSaving: Boolean = false,
    val saveSuccess: Boolean = false
)

class SettingsViewModel(
    private val settingsRepository: SettingsRepository,
    private val chatRepository: NanoChatRepository
) : ViewModel() {

    private val _uiState = MutableStateFlow(SettingsUiState())
    val uiState: StateFlow<SettingsUiState> = _uiState.asStateFlow()

    init {
        loadSettings()
        checkModelStatus()
    }

    private fun loadSettings() {
        viewModelScope.launch {
            settingsRepository.contextWindowSize.collect { size ->
                _uiState.value = _uiState.value.copy(contextWindowSize = size)
            }
        }
        viewModelScope.launch {
            settingsRepository.systemPrompt.collect { prompt ->
                _uiState.value = _uiState.value.copy(systemPrompt = prompt)
            }
        }
        viewModelScope.launch {
            settingsRepository.temperature.collect { temp ->
                _uiState.value = _uiState.value.copy(temperature = temp)
            }
        }
        viewModelScope.launch {
            settingsRepository.topK.collect { k ->
                _uiState.value = _uiState.value.copy(topK = k)
            }
        }
    }

    private fun checkModelStatus() {
        viewModelScope.launch {
            try {
                val status = chatRepository.checkModelStatus()
                _uiState.value = _uiState.value.copy(modelStatus = status)
            } catch (_: Exception) { }
        }
    }

    fun downloadModel() {
        if (_uiState.value.isDownloading) return
        _uiState.value = _uiState.value.copy(isDownloading = true, downloadProgress = 0f)

        viewModelScope.launch {
            try {
                chatRepository.downloadModel().collect { status ->
                    when (status) {
                        is DownloadStatus.DownloadStarted ->
                            Log.d("SettingsVM", "Model download started")
                        is DownloadStatus.DownloadProgress ->
                            _uiState.value = _uiState.value.copy(
                                downloadProgress = status.totalBytesDownloaded.toFloat()
                            )
                        DownloadStatus.DownloadCompleted -> {
                            _uiState.value = _uiState.value.copy(isDownloading = false)
                            checkModelStatus()
                        }
                        is DownloadStatus.DownloadFailed -> {
                            Log.e("SettingsVM", "Download failed", status.e)
                            _uiState.value = _uiState.value.copy(isDownloading = false)
                        }
                    }
                }
                // Flow completed
                _uiState.value = _uiState.value.copy(isDownloading = false)
                checkModelStatus()
            } catch (e: Exception) {
                _uiState.value = _uiState.value.copy(isDownloading = false)
            }
        }
    }

    fun updateContextWindowSize(size: String) {
        val intSize = size.toIntOrNull() ?: SettingsRepository.DEFAULT_CONTEXT_WINDOW_SIZE
        _uiState.value = _uiState.value.copy(contextWindowSize = intSize, saveSuccess = false)
    }

    fun updateSystemPrompt(prompt: String) {
        _uiState.value = _uiState.value.copy(systemPrompt = prompt, saveSuccess = false)
    }

    fun updateTemperature(value: Float) {
        _uiState.value = _uiState.value.copy(temperature = value, saveSuccess = false)
    }

    fun updateTopK(value: Int) {
        _uiState.value = _uiState.value.copy(topK = value, saveSuccess = false)
    }

    fun saveSettings() {
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isSaving = true)
            settingsRepository.saveSettings(
                contextWindowSize = _uiState.value.contextWindowSize,
                systemPrompt = _uiState.value.systemPrompt,
                temperature = _uiState.value.temperature,
                topK = _uiState.value.topK
            )
            _uiState.value = _uiState.value.copy(isSaving = false, saveSuccess = true)
        }
    }

    class Factory(
        private val settingsRepository: SettingsRepository,
        private val chatRepository: NanoChatRepository
    ) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            return SettingsViewModel(settingsRepository, chatRepository) as T
        }
    }
}
