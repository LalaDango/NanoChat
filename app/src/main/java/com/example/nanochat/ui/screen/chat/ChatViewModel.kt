package com.example.nanochat.ui.screen.chat

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.example.nanochat.data.local.ConversationEntity
import com.example.nanochat.data.local.MessageEntity
import com.example.nanochat.data.model.SummarizeConfig
import com.example.nanochat.data.repository.NanoChatRepository
import com.example.nanochat.data.repository.SettingsRepository
import com.example.nanochat.util.ProcessedAttachment
import com.google.gson.Gson
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

data class ChatUiState(
    val conversation: ConversationEntity? = null,
    val messages: List<MessageEntity> = emptyList(),
    val inputText: String = "",
    val isLoading: Boolean = false,
    val error: String? = null,
    val contextWindowSize: Int = SettingsRepository.DEFAULT_CONTEXT_WINDOW_SIZE,
    val imageAttachment: ProcessedAttachment.ImageAttachment? = null,
    val attachmentWarning: String? = null,
    val streamingContent: String = "",
    val showSummarizeDialog: Boolean = false,
    val summarizeTargetMessageId: Long? = null,
    val summarizeTargetContent: String? = null,
    val summarizePreview: NanoChatRepository.SummarizeResult? = null,
    val isSummarizePreviewLoading: Boolean = false,
    val summarizeInitialConfig: SummarizeConfig? = null,
    val summarizeToast: String? = null,
    val siblingInfoMap: Map<Long, NanoChatRepository.SiblingInfo> = emptyMap(),
    val editingMessageId: Long? = null,
    val editingText: String = "",
    // NanoChat additions
    val modelStatus: Int = -1,  // FeatureStatus values
    val contextIncludedIds: Set<Long> = emptySet()
)

class ChatViewModel(
    private val conversationId: Long,
    private val chatRepository: NanoChatRepository,
    private val settingsRepository: SettingsRepository
) : ViewModel() {

    private val _uiState = MutableStateFlow(ChatUiState())
    val uiState: StateFlow<ChatUiState> = _uiState.asStateFlow()

    init {
        loadConversation()
        loadMessages()
        loadContextWindowSize()
        checkModelStatus()
    }

    private fun loadConversation() {
        viewModelScope.launch {
            val conversation = chatRepository.getConversationById(conversationId)
            _uiState.value = _uiState.value.copy(conversation = conversation)
        }
    }

    private fun loadMessages() {
        viewModelScope.launch {
            chatRepository.getActivePathFlow(conversationId).collect { result ->
                _uiState.value = _uiState.value.copy(
                    messages = result.messages,
                    siblingInfoMap = result.siblingInfoMap
                )
                updateContextIncludedIds()
            }
        }
    }

    private fun loadContextWindowSize() {
        viewModelScope.launch {
            settingsRepository.contextWindowSize.collect { size ->
                _uiState.value = _uiState.value.copy(contextWindowSize = size)
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

    private fun updateContextIncludedIds() {
        viewModelScope.launch {
            try {
                val ids = chatRepository.getContextIncludedIds(conversationId)
                _uiState.value = _uiState.value.copy(contextIncludedIds = ids)
            } catch (_: Exception) { }
        }
    }

    fun updateInputText(text: String) {
        _uiState.value = _uiState.value.copy(inputText = text)
    }

    fun addAttachment(attachment: ProcessedAttachment) {
        when (attachment) {
            is ProcessedAttachment.TextAttachment -> {
                // Text attachments kept in code but not exposed in UI
            }
            is ProcessedAttachment.ImageAttachment -> {
                if (_uiState.value.imageAttachment != null) {
                    _uiState.value = _uiState.value.copy(
                        attachmentWarning = "画像は1枚までです"
                    )
                    return
                }
                _uiState.value = _uiState.value.copy(
                    imageAttachment = attachment,
                    attachmentWarning = null
                )
            }
        }
    }

    fun clearAllAttachments() {
        _uiState.value = _uiState.value.copy(
            imageAttachment = null, attachmentWarning = null
        )
    }

    fun removeImageAttachment() {
        _uiState.value = _uiState.value.copy(imageAttachment = null)
    }

    fun sendMessage() {
        val message = _uiState.value.inputText.trim()
        val imageAttachment = _uiState.value.imageAttachment
        if (message.isEmpty() && imageAttachment == null) return

        _uiState.value = _uiState.value.copy(
            inputText = "",
            imageAttachment = null,
            attachmentWarning = null,
            isLoading = true,
            error = null,
            streamingContent = ""
        )

        viewModelScope.launch {
            val result = chatRepository.sendMessage(
                conversationId = conversationId,
                userMessage = message,
                imageAttachment = imageAttachment,
                onStreamUpdate = { content ->
                    _uiState.value = _uiState.value.copy(
                        streamingContent = content
                    )
                }
            )
            result.fold(
                onSuccess = {
                    loadConversation()
                    _uiState.value = _uiState.value.copy(
                        isLoading = false,
                        streamingContent = ""
                    )
                },
                onFailure = { e ->
                    _uiState.value = _uiState.value.copy(
                        isLoading = false,
                        streamingContent = "",
                        error = e.message ?: "An error occurred"
                    )
                }
            )
        }
    }

    // ── Summarize ──

    fun openSummarizeDialog(messageId: Long, content: String) {
        _uiState.value = _uiState.value.copy(
            showSummarizeDialog = true,
            summarizeTargetMessageId = messageId,
            summarizeTargetContent = content,
            summarizePreview = null,
            isSummarizePreviewLoading = false,
            summarizeInitialConfig = null
        )
    }

    fun openResummarizeDialog(messageId: Long, content: String, configJson: String?) {
        val prevConfig = configJson?.let {
            try { Gson().fromJson(it, SummarizeConfig::class.java) } catch (_: Exception) { null }
        }
        _uiState.value = _uiState.value.copy(
            showSummarizeDialog = true,
            summarizeTargetMessageId = messageId,
            summarizeTargetContent = content,
            summarizePreview = null,
            isSummarizePreviewLoading = false,
            summarizeInitialConfig = prevConfig
        )
    }

    fun closeSummarizeDialog() {
        _uiState.value = _uiState.value.copy(
            showSummarizeDialog = false,
            summarizeTargetMessageId = null,
            summarizeTargetContent = null,
            summarizePreview = null,
            isSummarizePreviewLoading = false,
            summarizeInitialConfig = null
        )
    }

    fun generateSummarizePreview(config: SummarizeConfig) {
        val content = _uiState.value.summarizeTargetContent ?: return
        if (_uiState.value.isSummarizePreviewLoading) return

        _uiState.value = _uiState.value.copy(
            isSummarizePreviewLoading = true,
            summarizePreview = null
        )

        viewModelScope.launch {
            val result = chatRepository.generateSummary(content, config)
            result.fold(
                onSuccess = { preview ->
                    _uiState.value = _uiState.value.copy(
                        isSummarizePreviewLoading = false,
                        summarizePreview = preview
                    )
                },
                onFailure = { e ->
                    _uiState.value = _uiState.value.copy(
                        isSummarizePreviewLoading = false,
                        error = "要約プレビューに失敗しました: ${e.message}"
                    )
                }
            )
        }
    }

    fun confirmSummarizePreview() {
        val messageId = _uiState.value.summarizeTargetMessageId ?: return
        val preview = _uiState.value.summarizePreview ?: return

        viewModelScope.launch {
            chatRepository.saveSummary(messageId, preview.summaryText, preview.config)

            val toast = "要約完了！ ${preview.originalTokens} → ${preview.summaryTokens} トークン"
            _uiState.value = _uiState.value.copy(summarizeToast = toast)
            closeSummarizeDialog()
        }
    }

    fun toggleExcludeMessage(messageId: Long, currentlyExcluded: Boolean) {
        viewModelScope.launch {
            chatRepository.excludeMessage(messageId, !currentlyExcluded)
        }
    }

    fun clearSummarizeToast() {
        _uiState.value = _uiState.value.copy(summarizeToast = null)
    }

    fun clearError() {
        _uiState.value = _uiState.value.copy(error = null)
    }

    // ── Branch feature methods ──

    fun regenerateResponse() {
        _uiState.value = _uiState.value.copy(
            isLoading = true,
            error = null,
            streamingContent = ""
        )
        viewModelScope.launch {
            val result = chatRepository.regenerateLastResponse(
                conversationId = conversationId,
                onStreamUpdate = { content ->
                    _uiState.value = _uiState.value.copy(streamingContent = content)
                }
            )
            result.fold(
                onSuccess = {
                    _uiState.value = _uiState.value.copy(
                        isLoading = false,
                        streamingContent = ""
                    )
                },
                onFailure = { e ->
                    _uiState.value = _uiState.value.copy(
                        isLoading = false,
                        streamingContent = "",
                        error = e.message ?: "An error occurred"
                    )
                }
            )
        }
    }

    fun editMessage(messageId: Long) {
        val msg = _uiState.value.messages.find { it.id == messageId } ?: return
        _uiState.value = _uiState.value.copy(
            editingMessageId = messageId,
            editingText = msg.content
        )
    }

    fun updateEditText(text: String) {
        _uiState.value = _uiState.value.copy(editingText = text)
    }

    fun cancelEdit() {
        _uiState.value = _uiState.value.copy(editingMessageId = null, editingText = "")
    }

    fun submitEdit() {
        val messageId = _uiState.value.editingMessageId ?: return
        val newContent = _uiState.value.editingText.trim()
        if (newContent.isEmpty()) return

        _uiState.value = _uiState.value.copy(
            editingMessageId = null,
            editingText = "",
            isLoading = true,
            error = null,
            streamingContent = ""
        )

        viewModelScope.launch {
            val result = chatRepository.editAndResend(
                conversationId = conversationId,
                originalMessageId = messageId,
                newContent = newContent,
                onStreamUpdate = { content ->
                    _uiState.value = _uiState.value.copy(streamingContent = content)
                }
            )
            result.fold(
                onSuccess = {
                    _uiState.value = _uiState.value.copy(
                        isLoading = false,
                        streamingContent = ""
                    )
                },
                onFailure = { e ->
                    _uiState.value = _uiState.value.copy(
                        isLoading = false,
                        streamingContent = "",
                        error = e.message ?: "An error occurred"
                    )
                }
            )
        }
    }

    fun switchBranch(targetMessageId: Long) {
        viewModelScope.launch {
            chatRepository.switchBranch(conversationId, targetMessageId)
        }
    }

    class Factory(
        private val conversationId: Long,
        private val chatRepository: NanoChatRepository,
        private val settingsRepository: SettingsRepository
    ) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            return ChatViewModel(conversationId, chatRepository, settingsRepository) as T
        }
    }
}
