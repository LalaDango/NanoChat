package com.example.nanochat.ui.screen.chat

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.provider.OpenableColumns
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import android.graphics.Typeface
import android.widget.TextView
import androidx.compose.ui.viewinterop.AndroidView
import io.noties.markwon.Markwon
import io.noties.markwon.ext.strikethrough.StrikethroughPlugin
import io.noties.markwon.ext.tables.TablePlugin
import io.noties.markwon.html.HtmlPlugin
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.AttachFile
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.Description
import androidx.compose.material.icons.outlined.Summarize
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.VisibilityOff
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.TextButton
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.foundation.interaction.collectIsDraggedAsState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.nanochat.data.local.MessageEntity
import com.example.nanochat.data.repository.NanoChatRepository
import com.example.nanochat.data.repository.SettingsRepository
import com.example.nanochat.ui.theme.AssistantMessageBg
import com.example.nanochat.ui.theme.SummarizedAssistantMessageBg
import com.example.nanochat.ui.theme.SummarizedUserMessageBg
import com.example.nanochat.ui.theme.UserMessageBg
import com.example.nanochat.util.FileProcessor
import com.example.nanochat.util.ProcessedAttachment
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChatScreen(
    conversationId: Long,
    chatRepository: NanoChatRepository,
    settingsRepository: SettingsRepository,
    onBack: () -> Unit,
    viewModel: ChatViewModel = viewModel(
        factory = ChatViewModel.Factory(conversationId, chatRepository, settingsRepository)
    )
) {
    val uiState by viewModel.uiState.collectAsState()
    val listState = rememberLazyListState()
    val snackbarHostState = remember { SnackbarHostState() }
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    val filePickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri ->
        uri?.let {
            val cursor = context.contentResolver.query(it, null, null, null, null)
            val fileName = cursor?.use { c ->
                val nameIndex = c.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                c.moveToFirst()
                c.getString(nameIndex)
            } ?: "unknown"
            val mimeType = context.contentResolver.getType(it) ?: "application/octet-stream"

            try {
                val processed = FileProcessor.processFile(
                    context.contentResolver, it, fileName, mimeType
                )
                viewModel.addAttachment(processed)
            } catch (e: Exception) {
                scope.launch {
                    snackbarHostState.showSnackbar(
                        e.message ?: "ファイルの読み込みに失敗しました"
                    )
                }
            }
        }
    }

    var showOriginalTextFor by remember { mutableStateOf<MessageEntity?>(null) }

    showOriginalTextFor?.let { message ->
        OriginalTextDialog(
            content = message.content,
            onDismiss = { showOriginalTextFor = null }
        )
    }

    if (uiState.showSummarizeDialog) {
        SummarizeConfigDialog(
            initialConfig = uiState.summarizeInitialConfig,
            preview = uiState.summarizePreview,
            isLoading = uiState.isSummarizePreviewLoading,
            onGenerate = { config -> viewModel.generateSummarizePreview(config) },
            onConfirm = { viewModel.confirmSummarizePreview() },
            onDismiss = { viewModel.closeSummarizeDialog() }
        )
    }

    // Message height cache (prevents jumps from AndroidView re-measurement)
    val messageHeightCache = remember { mutableStateMapOf<Long, Int>() }

    // Auto-scroll flag
    var shouldAutoScroll by remember { mutableStateOf(true) }

    LaunchedEffect(uiState.isLoading) {
        if (uiState.isLoading) {
            shouldAutoScroll = true
        } else {
            shouldAutoScroll = false
        }
    }

    val isDragged by listState.interactionSource.collectIsDraggedAsState()

    val isAtBottom by remember {
        derivedStateOf {
            val info = listState.layoutInfo
            val lastVisible = info.visibleItemsInfo.lastOrNull()
            !listState.canScrollForward || (lastVisible != null &&
                lastVisible.index >= info.totalItemsCount - 1 &&
                lastVisible.offset + lastVisible.size <= info.viewportEndOffset + 300)
        }
    }

    LaunchedEffect(isAtBottom, isDragged) {
        if (isAtBottom && uiState.isLoading) {
            shouldAutoScroll = true
        } else if (isDragged && !isAtBottom) {
            shouldAutoScroll = false
        }
    }

    LaunchedEffect(shouldAutoScroll) {
        if (shouldAutoScroll) {
            snapshotFlow {
                val info = listState.layoutInfo
                info.totalItemsCount to (info.visibleItemsInfo.lastOrNull()?.size ?: 0)
            }.collect { (count, _) ->
                if (count > 0) {
                    listState.scrollToItem(count - 1, Int.MAX_VALUE)
                }
            }
        }
    }

    LaunchedEffect(uiState.error) {
        uiState.error?.let { error ->
            snackbarHostState.showSnackbar(error)
            viewModel.clearError()
        }
    }

    LaunchedEffect(uiState.attachmentWarning) {
        uiState.attachmentWarning?.let { warning ->
            snackbarHostState.showSnackbar(warning)
        }
    }

    LaunchedEffect(uiState.summarizeToast) {
        uiState.summarizeToast?.let { toast ->
            snackbarHostState.showSnackbar(toast)
            viewModel.clearSummarizeToast()
        }
    }

    // Model status label
    val modelStatusText = when (uiState.modelStatus) {
        3 -> "Nano ready"      // AVAILABLE
        2 -> "Downloading..."  // DOWNLOADING
        1 -> "Downloadable"    // DOWNLOADABLE
        0 -> "Unavailable"     // UNAVAILABLE
        else -> "..."          // checking
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(
                            text = run {
                                val conv = uiState.conversation
                                if (conv?.presetEmoji != null && conv.presetName != null) {
                                    "${conv.presetEmoji} ${conv.presetName}"
                                } else {
                                    conv?.title ?: "Chat"
                                }
                            },
                            maxLines = 1
                        )
                        Text(
                            text = modelStatusText,
                            style = MaterialTheme.typography.labelSmall,
                            color = if (uiState.modelStatus == 3)
                                MaterialTheme.colorScheme.primary
                            else
                                MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f)
                        )
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = "Back"
                        )
                    }
                }
            )
        },
        snackbarHost = { SnackbarHost(snackbarHostState) }
    ) { paddingValues ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
                .imePadding()
        ) {
        Column(
            modifier = Modifier.fillMaxSize()
        ) {
            LazyColumn(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp),
                state = listState,
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                items(
                    items = uiState.messages,
                    key = { it.id }
                ) { message ->
                    val density = LocalDensity.current
                    val cachedHeight = messageHeightCache[message.id]
                    val siblingInfo = uiState.siblingInfoMap[message.id]
                    val isEditing = uiState.editingMessageId == message.id
                    val isLastAssistant = message.role == "assistant" &&
                        message.id == uiState.messages.lastOrNull { it.role == "assistant" }?.id
                    val editingIdx = uiState.editingMessageId?.let { editId ->
                        uiState.messages.indexOfFirst { it.id == editId }
                    }
                    val messageIdx = uiState.messages.indexOf(message)
                    val isAfterEditing = editingIdx != null && messageIdx > editingIdx
                    val isInContext = message.id in uiState.contextIncludedIds

                    if (isEditing) {
                        EditableMessageBubble(
                            editText = uiState.editingText,
                            onTextChange = viewModel::updateEditText,
                            onSubmit = viewModel::submitEdit,
                            onCancel = viewModel::cancelEdit
                        )
                    } else {
                    MessageBubble(
                        message = message,
                        isInContext = isInContext,
                        onCopyMessage = { content ->
                            copyToClipboard(context, content)
                        },
                        onSummarize = {
                            if (message.isSummarized) {
                                viewModel.openResummarizeDialog(message.id, message.content, message.summarizeConfigJson)
                            } else {
                                viewModel.openSummarizeDialog(message.id, message.content)
                            }
                        },
                        onShowOriginal = {
                            showOriginalTextFor = message
                        },
                        onExcludeToggle = {
                            viewModel.toggleExcludeMessage(message.id, message.isExcluded)
                        },
                        siblingInfo = siblingInfo,
                        onSwitchBranch = viewModel::switchBranch,
                        onEdit = if (message.role == "user" && !uiState.isLoading) {
                            { viewModel.editMessage(message.id) }
                        } else null,
                        onRegenerate = if (isLastAssistant && !uiState.isLoading) {
                            { viewModel.regenerateResponse() }
                        } else null,
                        modifier = ((if (cachedHeight != null) {
                            Modifier.defaultMinSize(minHeight = with(density) { cachedHeight.toDp() })
                        } else {
                            Modifier
                        }).onGloballyPositioned { coordinates ->
                            val height = coordinates.size.height
                            if (height > 0 && messageHeightCache[message.id] != height) {
                                messageHeightCache[message.id] = height
                            }
                        }).alpha(if (isAfterEditing) 0.35f else 1f)
                    )
                    }
                }

                if (uiState.isLoading) {
                    item(key = "streaming") {
                        if (uiState.streamingContent.isNotEmpty()) {
                            StreamingMessageBubble(content = uiState.streamingContent)
                        } else {
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(16.dp),
                                contentAlignment = Alignment.Center
                            ) {
                                CircularProgressIndicator(
                                    modifier = Modifier.size(24.dp)
                                )
                            }
                        }
                    }
                }
            }

            val isEditMode = uiState.editingMessageId != null
            ChatInput(
                text = uiState.inputText,
                onTextChange = viewModel::updateInputText,
                onSend = viewModel::sendMessage,
                onAttachClick = {
                    filePickerLauncher.launch(
                        arrayOf(
                            "image/jpeg",
                            "image/png"
                        )
                    )
                },
                isLoading = uiState.isLoading,
                imageAttachment = uiState.imageAttachment,
                onRemoveImageAttachment = { viewModel.removeImageAttachment() },
                modifier = Modifier
                    .padding(16.dp)
                    .alpha(if (isEditMode) 0.4f else 1f)
                    .then(if (isEditMode) Modifier.clickable(enabled = false) {} else Modifier)
            )
        }

        }
    }
}

@Composable
private fun StreamingMessageBubble(content: String) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.Start
    ) {
        Box(
            modifier = Modifier
                .widthIn(max = 340.dp)
                .background(
                    color = AssistantMessageBg,
                    shape = RoundedCornerShape(
                        topStart = 16.dp,
                        topEnd = 16.dp,
                        bottomStart = 4.dp,
                        bottomEnd = 16.dp
                    )
                )
        ) {
            val textColor = MaterialTheme.colorScheme.onSurface.toArgb()

            Column(modifier = Modifier.padding(12.dp)) {
                SelectableText(
                    text = content,
                    textColor = textColor,
                    textSizeSp = 14f
                )
            }
        }
    }
}

private fun copyToClipboard(context: Context, text: String) {
    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
    val clip = ClipData.newPlainText("message", text)
    clipboard.setPrimaryClip(clip)
    Toast.makeText(context, "Copied to clipboard", Toast.LENGTH_SHORT).show()
}

// Native TextView with Markwon for Markdown rendering + full text selection
@Composable
private fun SelectableText(
    text: String,
    textColor: Int,
    textSizeSp: Float = 14f,
    isItalic: Boolean = false,
    modifier: Modifier = Modifier
) {
    AndroidView(
        factory = { context ->
            val markwon = Markwon.builder(context)
                .usePlugin(TablePlugin.create(context))
                .usePlugin(HtmlPlugin.create())
                .usePlugin(StrikethroughPlugin.create())
                .build()

            TextView(context).apply {
                setTextIsSelectable(true)
                setTextColor(textColor)
                textSize = textSizeSp
                if (isItalic) {
                    setTypeface(typeface, Typeface.ITALIC)
                }
                tag = markwon
            }
        },
        update = { textView ->
            val markwon = textView.tag as Markwon
            markwon.setMarkdown(textView, text)
        },
        modifier = modifier.fillMaxWidth()
    )
}

@Composable
private fun MessageBubble(
    message: MessageEntity,
    isInContext: Boolean,
    onCopyMessage: (String) -> Unit,
    onSummarize: () -> Unit,
    onShowOriginal: () -> Unit,
    onExcludeToggle: () -> Unit,
    siblingInfo: NanoChatRepository.SiblingInfo? = null,
    onSwitchBranch: (Long) -> Unit = {},
    onEdit: (() -> Unit)? = null,
    onRegenerate: (() -> Unit)? = null,
    modifier: Modifier = Modifier
) {
    val isUser = message.role == "user"
    val displayContent = if (message.isSummarized && message.summaryText != null) {
        message.summaryText
    } else {
        message.content
    }

    val bgColor = if (message.isSummarized) {
        if (isUser) SummarizedUserMessageBg else SummarizedAssistantMessageBg
    } else {
        if (isUser) UserMessageBg else AssistantMessageBg
    }

    // Alpha: manual exclude or auto-trim (out of context)
    val effectiveAlpha = when {
        message.isExcluded -> 0.45f
        !isInContext -> 0.45f
        else -> 1f
    }

    Row(
        modifier = modifier
            .fillMaxWidth()
            .alpha(effectiveAlpha),
        horizontalArrangement = if (isUser) Arrangement.End else Arrangement.Start
    ) {
        Column {
            Box(
                modifier = Modifier
                    .widthIn(max = 340.dp)
                    .background(
                        color = bgColor,
                        shape = RoundedCornerShape(
                            topStart = 16.dp,
                            topEnd = 16.dp,
                            bottomStart = if (isUser) 16.dp else 4.dp,
                            bottomEnd = if (isUser) 4.dp else 16.dp
                        )
                    )
            ) {
                val textColor = MaterialTheme.colorScheme.onSurface.toArgb()

                Column(
                    modifier = Modifier.padding(12.dp)
                ) {
                    SelectableText(
                        text = displayContent,
                        textColor = textColor,
                        textSizeSp = 14f
                    )

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.End,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        // Branch navigation
                        if (siblingInfo != null && siblingInfo.totalSiblings > 1) {
                            BranchNavigator(
                                siblingInfo = siblingInfo,
                                onPrevious = {
                                    val prevIdx = siblingInfo.currentIndex - 1
                                    if (prevIdx >= 0) onSwitchBranch(siblingInfo.siblingIds[prevIdx])
                                },
                                onNext = {
                                    val nextIdx = siblingInfo.currentIndex + 1
                                    if (nextIdx < siblingInfo.totalSiblings) onSwitchBranch(siblingInfo.siblingIds[nextIdx])
                                }
                            )
                        }
                        // Edit button (user messages only)
                        if (onEdit != null) {
                            IconButton(
                                onClick = onEdit,
                                modifier = Modifier.size(32.dp)
                            ) {
                                Icon(
                                    imageVector = Icons.Outlined.Edit,
                                    contentDescription = "Edit message",
                                    modifier = Modifier.size(18.dp),
                                    tint = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f)
                                )
                            }
                        }
                        // Regenerate button (last assistant message only)
                        if (onRegenerate != null) {
                            IconButton(
                                onClick = onRegenerate,
                                modifier = Modifier.size(32.dp)
                            ) {
                                Icon(
                                    imageVector = Icons.Outlined.Refresh,
                                    contentDescription = "Regenerate response",
                                    modifier = Modifier.size(18.dp),
                                    tint = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f)
                                )
                            }
                        }
                        IconButton(
                            onClick = onExcludeToggle,
                            modifier = Modifier.size(32.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Outlined.VisibilityOff,
                                contentDescription = if (message.isExcluded) "Include in context" else "Exclude from context",
                                modifier = Modifier.size(18.dp),
                                tint = if (message.isExcluded) {
                                    MaterialTheme.colorScheme.error.copy(alpha = 0.7f)
                                } else {
                                    MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f)
                                }
                            )
                        }
                        // Summarize button
                        IconButton(
                            onClick = onSummarize,
                            modifier = Modifier.size(32.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Outlined.Summarize,
                                contentDescription = if (message.isSummarized) "Re-summarize" else "Summarize",
                                modifier = Modifier.size(18.dp),
                                tint = if (message.isSummarized) {
                                    MaterialTheme.colorScheme.primary.copy(alpha = 0.7f)
                                } else {
                                    MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f)
                                }
                            )
                        }
                        // Show Original button (only for summarized messages)
                        if (message.isSummarized) {
                            IconButton(
                                onClick = onShowOriginal,
                                modifier = Modifier.size(32.dp)
                            ) {
                                Icon(
                                    imageVector = Icons.Outlined.Description,
                                    contentDescription = "Show original",
                                    modifier = Modifier.size(18.dp),
                                    tint = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f)
                                )
                            }
                        }
                        CopyButton(onClick = { onCopyMessage(displayContent) })
                    }
                }
            }

            // "context外" label for auto-trimmed messages
            if (!isInContext && !message.isExcluded) {
                Text(
                    text = "context外",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.4f),
                    modifier = Modifier.padding(top = 2.dp)
                )
            }
        }
    }
}

@Composable
private fun BranchNavigator(
    siblingInfo: NanoChatRepository.SiblingInfo,
    onPrevious: () -> Unit,
    onNext: () -> Unit
) {
    Row(
        verticalAlignment = Alignment.CenterVertically
    ) {
        IconButton(
            onClick = onPrevious,
            enabled = siblingInfo.currentIndex > 0,
            modifier = Modifier.size(28.dp)
        ) {
            Icon(
                imageVector = Icons.AutoMirrored.Filled.KeyboardArrowLeft,
                contentDescription = "Previous branch",
                modifier = Modifier.size(18.dp),
                tint = if (siblingInfo.currentIndex > 0)
                    MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f)
                else
                    MaterialTheme.colorScheme.onSurface.copy(alpha = 0.2f)
            )
        }
        Text(
            text = "${siblingInfo.currentIndex + 1}/${siblingInfo.totalSiblings}",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
        )
        IconButton(
            onClick = onNext,
            enabled = siblingInfo.currentIndex < siblingInfo.totalSiblings - 1,
            modifier = Modifier.size(28.dp)
        ) {
            Icon(
                imageVector = Icons.AutoMirrored.Filled.KeyboardArrowRight,
                contentDescription = "Next branch",
                modifier = Modifier.size(18.dp),
                tint = if (siblingInfo.currentIndex < siblingInfo.totalSiblings - 1)
                    MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f)
                else
                    MaterialTheme.colorScheme.onSurface.copy(alpha = 0.2f)
            )
        }
    }
}

@Composable
private fun EditableMessageBubble(
    editText: String,
    onTextChange: (String) -> Unit,
    onSubmit: () -> Unit,
    onCancel: () -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.End
    ) {
        Box(
            modifier = Modifier
                .widthIn(max = 340.dp)
                .background(
                    color = UserMessageBg,
                    shape = RoundedCornerShape(16.dp)
                )
        ) {
            Column(
                modifier = Modifier.padding(12.dp)
            ) {
                OutlinedTextField(
                    value = editText,
                    onValueChange = onTextChange,
                    modifier = Modifier.fillMaxWidth(),
                    textStyle = MaterialTheme.typography.bodyMedium,
                    minLines = 2,
                    maxLines = 10
                )
                Spacer(modifier = Modifier.height(8.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End
                ) {
                    TextButton(onClick = onCancel) {
                        Text("キャンセル")
                    }
                    TextButton(
                        onClick = onSubmit,
                        enabled = editText.isNotBlank()
                    ) {
                        Text("保存")
                    }
                }
            }
        }
    }
}

@Composable
private fun CopyButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    IconButton(
        onClick = onClick,
        modifier = modifier.size(32.dp)
    ) {
        Icon(
            imageVector = Icons.Outlined.ContentCopy,
            contentDescription = "Copy message",
            modifier = Modifier.size(18.dp),
            tint = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f)
        )
    }
}

@Composable
private fun OriginalTextDialog(
    content: String,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Original Text") },
        text = {
            val scrollState = rememberScrollState()
            val textColor = MaterialTheme.colorScheme.onSurface.toArgb()
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(scrollState)
            ) {
                SelectableText(
                    text = content,
                    textColor = textColor,
                    textSizeSp = 13f
                )
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text("Close")
            }
        }
    )
}

@Composable
private fun ChatInput(
    text: String,
    onTextChange: (String) -> Unit,
    onSend: () -> Unit,
    onAttachClick: () -> Unit,
    isLoading: Boolean,
    imageAttachment: ProcessedAttachment.ImageAttachment?,
    onRemoveImageAttachment: () -> Unit,
    modifier: Modifier = Modifier
) {
    val canSend = (text.isNotBlank() || imageAttachment != null) && !isLoading

    Column(
        modifier = modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surface)
    ) {
        if (imageAttachment != null) {
            AttachmentPreviewBar(
                imageAttachment = imageAttachment,
                onRemoveImageAttachment = onRemoveImageAttachment,
                modifier = Modifier.padding(bottom = 4.dp)
            )
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(
                onClick = onAttachClick,
                enabled = !isLoading
            ) {
                Icon(
                    imageVector = Icons.Default.AttachFile,
                    contentDescription = "Attach image",
                    tint = if (!isLoading)
                        MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
                    else
                        MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f)
                )
            }

            OutlinedTextField(
                value = text,
                onValueChange = onTextChange,
                modifier = Modifier.weight(1f),
                placeholder = { Text("Type a message...") },
                maxLines = 6,
                enabled = !isLoading
            )

            IconButton(
                onClick = onSend,
                enabled = canSend
            ) {
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.Send,
                    contentDescription = "Send",
                    tint = if (canSend)
                        MaterialTheme.colorScheme.primary
                    else
                        MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f)
                )
            }
        }
    }
}

@Composable
private fun AttachmentPreviewBar(
    imageAttachment: ProcessedAttachment.ImageAttachment,
    onRemoveImageAttachment: () -> Unit,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .background(
                MaterialTheme.colorScheme.surfaceVariant,
                RoundedCornerShape(8.dp)
            )
            .padding(8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(
            text = imageAttachment.fileName,
            style = MaterialTheme.typography.bodySmall,
            modifier = Modifier.weight(1f)
        )
        IconButton(
            onClick = onRemoveImageAttachment,
            modifier = Modifier.size(24.dp)
        ) {
            Text("✕", style = MaterialTheme.typography.labelSmall)
        }
    }
}
