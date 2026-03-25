package com.example.nanochat.data.repository

import android.content.Context
import android.graphics.BitmapFactory
import android.util.Base64
import android.util.Log
import androidx.room.withTransaction
import com.example.nanochat.data.local.AppDatabase
import com.example.nanochat.data.local.ConversationDao
import com.example.nanochat.data.local.ConversationEntity
import com.example.nanochat.data.local.MessageDao
import com.example.nanochat.data.local.MessageEntity
import com.example.nanochat.data.local.PresetEntity
import com.example.nanochat.data.model.BulletCount
import com.example.nanochat.data.model.SummarizeConfig
import com.example.nanochat.util.ProcessedAttachment
import com.google.gson.Gson
import com.google.mlkit.genai.common.FeatureStatus
import com.google.mlkit.genai.prompt.GenerativeModel
import com.google.mlkit.genai.prompt.Generation
import com.google.mlkit.genai.prompt.ImagePart
import com.google.mlkit.genai.prompt.TextPart
import com.google.mlkit.genai.prompt.generateContentRequest
import com.google.mlkit.genai.summarization.Summarization
import com.google.mlkit.genai.summarization.SummarizationRequest
import com.google.mlkit.genai.summarization.SummarizerOptions
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import androidx.concurrent.futures.await
import kotlinx.coroutines.withContext

class NanoChatRepository(
    private val context: Context,
    private val database: AppDatabase,
    private val conversationDao: ConversationDao,
    private val messageDao: MessageDao,
    private val settingsRepository: SettingsRepository
) {
    private val gson = Gson()
    private val generativeModel: GenerativeModel = Generation.getClient()

    // ── Model status ──

    suspend fun checkModelStatus(): Int {
        return generativeModel.checkStatus()
    }

    fun downloadModel() = generativeModel.download()

    suspend fun warmup() {
        withContext(Dispatchers.IO) {
            try {
                generativeModel.warmup()
            } catch (e: Exception) {
                Log.w("NanoChatRepository", "warmup failed: ${e.message}")
            }
        }
    }

    // ── Conversation CRUD ──

    fun getAllConversations(): Flow<List<ConversationEntity>> {
        return conversationDao.getAllConversations()
    }

    suspend fun getConversationById(id: Long): ConversationEntity? {
        return conversationDao.getConversationById(id)
    }

    fun getMessagesForConversation(conversationId: Long): Flow<List<MessageEntity>> {
        return messageDao.getMessagesForConversation(conversationId)
    }

    suspend fun createConversation(title: String): Long {
        val conversation = ConversationEntity(title = title)
        return conversationDao.insert(conversation)
    }

    suspend fun createConversationWithPreset(title: String, preset: PresetEntity): Long {
        val conversation = ConversationEntity(
            title = title,
            presetId = preset.id,
            presetEmoji = preset.emoji,
            presetName = preset.name,
            systemPrompt = preset.systemPrompt
        )
        return conversationDao.insert(conversation)
    }

    suspend fun updateConversationTitle(id: Long, title: String) {
        val conversation = conversationDao.getConversationById(id)
        if (conversation != null) {
            conversationDao.update(
                conversation.copy(
                    title = title,
                    updatedAt = System.currentTimeMillis()
                )
            )
        }
    }

    suspend fun deleteConversation(id: Long) {
        conversationDao.deleteById(id)
    }

    // ── Message operations ──

    suspend fun addMessage(
        conversationId: Long,
        role: String,
        content: String,
        parentMessageId: Long? = null,
        siblingIndex: Int = 0
    ): Long {
        val message = MessageEntity(
            conversationId = conversationId,
            role = role,
            content = content,
            parentMessageId = parentMessageId,
            siblingIndex = siblingIndex
        )
        val messageId = database.withTransaction {
            val newId = messageDao.insert(message)
            if (parentMessageId != null) {
                messageDao.updateActiveChildId(parentMessageId, newId)
            } else {
                conversationDao.updateActiveRootMessageId(conversationId, newId)
            }
            newId
        }

        val conversation = conversationDao.getConversationById(conversationId)
        if (conversation != null) {
            conversationDao.update(conversation.copy(updatedAt = System.currentTimeMillis()))
        }

        return messageId
    }

    // ── Send message (ML Kit Prompt API) ──

    suspend fun sendMessage(
        conversationId: Long,
        userMessage: String,
        imageAttachment: ProcessedAttachment.ImageAttachment? = null,
        onStreamUpdate: ((content: String) -> Unit)? = null
    ): Result<String> {
        return try {
            val dbContent = buildString {
                if (userMessage.isNotBlank()) append(userMessage)
                if (imageAttachment != null) {
                    if (isNotEmpty()) append("\n\n")
                    append("[画像添付: ${imageAttachment.fileName}]")
                }
            }

            val activePath = getActivePathMessages(conversationId)
            val parentId = activePath.lastOrNull()?.id

            val userMsgId = addMessage(conversationId, "user", dbContent, parentMessageId = parentId)

            generateResponse(
                conversationId = conversationId,
                parentMessageId = userMsgId,
                imageAttachment = imageAttachment,
                userMessage = userMessage,
                onStreamUpdate = onStreamUpdate
            )
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    private suspend fun generateResponse(
        conversationId: Long,
        parentMessageId: Long,
        siblingIndex: Int = 0,
        imageAttachment: ProcessedAttachment.ImageAttachment? = null,
        userMessage: String = "",
        onStreamUpdate: ((content: String) -> Unit)? = null
    ): Result<String> {
        val temperature = settingsRepository.temperature.first()
        val topK = settingsRepository.topK.first()

        // Build prompt from conversation history
        val contextResult = buildPromptFromHistory(conversationId, upToMessageId = parentMessageId)
        val prompt = contextResult.prompt

        val fullResponse = StringBuilder()

        val maxRetries = 3

        return try {
            withContext(Dispatchers.IO) {
                val request = if (imageAttachment != null) {
                    // Decode base64 to Bitmap at send time only
                    val imageBytes = Base64.decode(imageAttachment.base64Data, Base64.DEFAULT)
                    val bitmap = BitmapFactory.decodeByteArray(imageBytes, 0, imageBytes.size)
                    generateContentRequest(ImagePart(bitmap), TextPart(prompt)) {
                        this.temperature = temperature
                        this.topK = topK
                    }
                } else {
                    generateContentRequest(TextPart(prompt)) {
                        this.temperature = temperature
                        this.topK = topK
                    }
                }

                var lastEmitTime = 0L
                val throttleMs = 32L // ~30fps
                var lastError: Exception? = null

                for (attempt in 1..maxRetries) {
                    try {
                        fullResponse.clear()
                        generativeModel.generateContentStream(request).collect { chunk ->
                            val text = chunk.candidates.firstOrNull()?.text ?: ""
                            fullResponse.append(text)

                            if (onStreamUpdate != null && text.isNotEmpty()) {
                                val now = System.currentTimeMillis()
                                if (now - lastEmitTime >= throttleMs) {
                                    lastEmitTime = now
                                    withContext(Dispatchers.Main) {
                                        onStreamUpdate(fullResponse.toString())
                                    }
                                }
                            }
                        }

                        // Final emit
                        withContext(Dispatchers.Main) {
                            onStreamUpdate?.invoke(fullResponse.toString())
                        }
                        lastError = null
                        break // Success
                    } catch (e: Exception) {
                        lastError = e
                        val message = e.message ?: ""
                        // Retry on BUSY; give up on non-transient errors
                        if (message.contains("BUSY", ignoreCase = true) && attempt < maxRetries) {
                            val backoffMs = 1000L * (1L shl (attempt - 1)) // 1s, 2s
                            Log.w("NanoChatRepository", "Model BUSY, retrying in ${backoffMs}ms (attempt $attempt)")
                            delay(backoffMs)
                            continue
                        }
                        throw translateMlKitError(e)
                    }
                }

                if (lastError != null) throw translateMlKitError(lastError!!)
            }

            val assistantMessage = fullResponse.toString().ifEmpty { "No response received" }

            addMessage(
                conversationId = conversationId,
                role = "assistant",
                content = assistantMessage,
                parentMessageId = parentMessageId,
                siblingIndex = siblingIndex
            )

            updateConversationTitleIfNeeded(conversationId)
            Result.success(assistantMessage)
        } catch (e: Exception) {
            Log.e("NanoChatRepository", "generateResponse failed", e)
            Result.failure(e)
        }
    }

    // ── System prompt resolution ──

    private suspend fun resolveSystemPrompt(conversationId: Long): String {
        val conversation = conversationDao.getConversationById(conversationId)
        return conversation?.systemPrompt
            ?: settingsRepository.systemPrompt.first()
    }

    // ── Context building (4k token constraint) ──

    data class ContextBuildResult(
        val prompt: String,
        val includedMessageIds: Set<Long>
    )

    suspend fun buildPromptFromHistory(
        conversationId: Long,
        upToMessageId: Long? = null
    ): ContextBuildResult {
        val systemPrompt = resolveSystemPrompt(conversationId)
        val maxTokens = settingsRepository.contextWindowSize.first()
        var activePath = getActivePathMessages(conversationId)

        if (upToMessageId != null) {
            val idx = activePath.indexOfFirst { it.id == upToMessageId }
            if (idx >= 0) activePath = activePath.subList(0, idx + 1)
        }

        // Filter manually excluded messages
        val candidates = activePath.filter { !it.isExcluded }

        // Reserve tokens for system prompt and output
        val systemTokens = if (systemPrompt.isNotBlank()) estimateTokens("System: $systemPrompt\n\n") else 0
        val budget = maxTokens - systemTokens - 200  // Reserve 200 tokens for output

        // Build from newest to oldest, staying within budget
        var tokenCount = 0
        val included = mutableListOf<MessageEntity>()
        for (msg in candidates.reversed()) {
            val content = if (msg.isSummarized && msg.summaryText != null) msg.summaryText else msg.content
            val role = if (msg.role == "user") "User" else "Assistant"
            val estimated = estimateTokens("$role: $content\n\n")
            if (tokenCount + estimated > budget) break
            included.add(0, msg)
            tokenCount += estimated
        }

        val prompt = buildString {
            if (systemPrompt.isNotBlank()) {
                appendLine("System: $systemPrompt")
                appendLine()
            }
            for (msg in included) {
                val role = if (msg.role == "user") "User" else "Assistant"
                val content = if (msg.isSummarized && msg.summaryText != null) msg.summaryText else msg.content
                appendLine("$role: $content")
                appendLine()
            }
        }

        return ContextBuildResult(prompt, included.map { it.id }.toSet())
    }

    // Token estimation: ~2 chars/token for mixed JP/EN text (safe side)
    fun estimateTokens(text: String): Int {
        return (text.length / 2).coerceAtLeast(1)
    }

    suspend fun getContextIncludedIds(conversationId: Long): Set<Long> {
        return buildPromptFromHistory(conversationId).includedMessageIds
    }

    // ── Regenerate / Edit ──

    suspend fun regenerateLastResponse(
        conversationId: Long,
        onStreamUpdate: ((content: String) -> Unit)? = null
    ): Result<String> {
        return try {
            val activePath = getActivePathMessages(conversationId)
            val lastAssistant = activePath.lastOrNull { it.role == "assistant" }
                ?: return Result.failure(Exception("No assistant message to regenerate"))

            val parentId = lastAssistant.parentMessageId
                ?: return Result.failure(Exception("Assistant message has no parent"))

            val siblingIndex = messageDao.getSiblingCount(parentId)

            generateResponse(
                conversationId = conversationId,
                parentMessageId = parentId,
                siblingIndex = siblingIndex,
                onStreamUpdate = onStreamUpdate
            )
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun editAndResend(
        conversationId: Long,
        originalMessageId: Long,
        newContent: String,
        onStreamUpdate: ((content: String) -> Unit)? = null
    ): Result<String> {
        return try {
            val originalMsg = messageDao.getMessageById(originalMessageId)
                ?: return Result.failure(Exception("Original message not found"))

            val grandparentId = originalMsg.parentMessageId
            val siblingIndex = if (grandparentId != null) {
                messageDao.getSiblingCount(grandparentId)
            } else {
                messageDao.getRootSiblingCount(originalMsg.conversationId)
            }

            val userMsgId = addMessage(
                conversationId = conversationId,
                role = "user",
                content = newContent,
                parentMessageId = grandparentId,
                siblingIndex = siblingIndex
            )

            generateResponse(
                conversationId = conversationId,
                parentMessageId = userMsgId,
                onStreamUpdate = onStreamUpdate
            )
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    // ── Branch feature: active path resolution ──

    data class SiblingInfo(
        val currentIndex: Int,
        val totalSiblings: Int,
        val siblingIds: List<Long>
    )

    data class ActivePathResult(
        val messages: List<MessageEntity>,
        val siblingInfoMap: Map<Long, SiblingInfo>
    )

    fun getActivePathFlow(conversationId: Long): Flow<ActivePathResult> {
        return messageDao.getMessagesForConversation(conversationId).map { allMessages ->
            if (allMessages.isEmpty()) return@map ActivePathResult(emptyList(), emptyMap())

            val conversation = conversationDao.getConversationById(conversationId)

            if (conversation?.activeRootMessageId == null && allMessages.isNotEmpty()) {
                val needsMigration = allMessages.none { it.parentMessageId != null || it.activeChildId != null }
                if (needsMigration) {
                    migrateConversationIfNeeded(conversationId, allMessages)
                    val freshMessages = messageDao.getMessagesForConversationSync(conversationId)
                    val freshConversation = conversationDao.getConversationById(conversationId)
                    return@map computeActivePathInternal(freshConversation, freshMessages)
                }
            }

            computeActivePathInternal(conversation, allMessages)
        }
    }

    suspend fun getActivePathMessages(conversationId: Long): List<MessageEntity> {
        val allMessages = messageDao.getMessagesForConversationSync(conversationId)
        if (allMessages.isEmpty()) return emptyList()

        val conversation = conversationDao.getConversationById(conversationId)

        if (conversation?.activeRootMessageId == null && allMessages.isNotEmpty()) {
            val needsMigration = allMessages.none { it.parentMessageId != null || it.activeChildId != null }
            if (needsMigration) {
                migrateConversationIfNeeded(conversationId, allMessages)
                val freshMessages = messageDao.getMessagesForConversationSync(conversationId)
                val freshConversation = conversationDao.getConversationById(conversationId)
                return computeActivePathInternal(freshConversation, freshMessages).messages
            }
        }

        return computeActivePathInternal(conversation, allMessages).messages
    }

    private fun computeActivePathInternal(
        conversation: ConversationEntity?,
        allMessages: List<MessageEntity>
    ): ActivePathResult {
        if (allMessages.isEmpty()) return ActivePathResult(emptyList(), emptyMap())

        val msgMap = allMessages.associateBy { it.id }

        val rootMsg = conversation?.activeRootMessageId?.let { msgMap[it] }
            ?: allMessages.filter { it.parentMessageId == null }.minByOrNull { it.createdAt }
            ?: return ActivePathResult(emptyList(), emptyMap())

        // Walk the activeChildId chain
        val path = mutableListOf<MessageEntity>()
        var current: MessageEntity? = rootMsg
        val visited = mutableSetOf<Long>()
        while (current != null) {
            if (!visited.add(current.id)) break
            path.add(current)
            val childId = current.activeChildId ?: break
            current = msgMap[childId]
        }

        // Compute sibling info
        val childrenByParent = allMessages.filter { it.parentMessageId != null }.groupBy { it.parentMessageId }
        val rootMessages = allMessages.filter { it.parentMessageId == null }.sortedBy { it.siblingIndex }

        val siblingInfoMap = mutableMapOf<Long, SiblingInfo>()
        for (msg in path) {
            val siblings = if (msg.parentMessageId == null) {
                rootMessages
            } else {
                childrenByParent[msg.parentMessageId]?.sortedBy { it.siblingIndex } ?: listOf(msg)
            }

            if (siblings.size > 1) {
                val currentIdx = siblings.indexOfFirst { it.id == msg.id }
                siblingInfoMap[msg.id] = SiblingInfo(
                    currentIndex = if (currentIdx >= 0) currentIdx else 0,
                    totalSiblings = siblings.size,
                    siblingIds = siblings.map { it.id }
                )
            }
        }

        return ActivePathResult(path, siblingInfoMap)
    }

    private suspend fun migrateConversationIfNeeded(conversationId: Long, allMessages: List<MessageEntity>) {
        database.withTransaction {
            val sorted = allMessages.sortedBy { it.createdAt }
            for (i in sorted.indices) {
                val parentId = if (i > 0) sorted[i - 1].id else null
                val activeChild = if (i < sorted.lastIndex) sorted[i + 1].id else null
                messageDao.updateParentAndIndex(sorted[i].id, parentId, 0)
                messageDao.updateActiveChildId(sorted[i].id, activeChild)
            }
            if (sorted.isNotEmpty()) {
                conversationDao.updateActiveRootMessageId(conversationId, sorted.first().id)
            }
        }
    }

    suspend fun switchBranch(conversationId: Long, targetMessageId: Long) {
        val targetMsg = messageDao.getMessageById(targetMessageId) ?: return
        if (targetMsg.parentMessageId == null) {
            conversationDao.updateActiveRootMessageId(conversationId, targetMessageId)
            messageDao.updateActiveChildId(targetMessageId, targetMsg.activeChildId)
        } else {
            messageDao.updateActiveChildId(targetMsg.parentMessageId, targetMessageId)
        }
    }

    // ── Exclude / Summarize ──

    suspend fun excludeMessage(messageId: Long, isExcluded: Boolean) {
        messageDao.updateExcluded(messageId, isExcluded)
    }

    data class SummarizeResult(
        val summaryText: String,
        val originalTokens: Int,
        val summaryTokens: Int,
        val config: SummarizeConfig
    )

    suspend fun generateSummary(content: String, config: SummarizeConfig): Result<SummarizeResult> {
        return try {
            val outputType = when (config.bulletCount) {
                BulletCount.ONE -> SummarizerOptions.OutputType.ONE_BULLET
                BulletCount.TWO -> SummarizerOptions.OutputType.TWO_BULLETS
                BulletCount.THREE -> SummarizerOptions.OutputType.THREE_BULLETS
            }

            val summarizer = Summarization.getClient(
                SummarizerOptions.builder(context)
                    .setInputType(SummarizerOptions.InputType.CONVERSATION)
                    .setOutputType(outputType)
                    .setLanguage(SummarizerOptions.Language.JAPANESE)
                    .build()
            )

            try {
                var status = summarizer.checkFeatureStatus().await()
                if (status == FeatureStatus.DOWNLOADABLE) {
                    // Auto-download summarization model
                    summarizer.downloadFeature(object : com.google.mlkit.genai.common.DownloadCallback {
                        override fun onDownloadStarted(bytesToDownload: Long) {}
                        override fun onDownloadProgress(totalBytesDownloaded: Long) {}
                        override fun onDownloadCompleted() {}
                        override fun onDownloadFailed(e: com.google.mlkit.genai.common.GenAiException) {}
                    }).await()
                    status = summarizer.checkFeatureStatus().await()
                }
                if (status != FeatureStatus.AVAILABLE) {
                    return Result.failure(Exception("要約機能が利用できません: $status"))
                }

                val request = SummarizationRequest.builder(content).build()

                val result = summarizer.runInference(request).await()
                val summaryText = result.summary?.toString()

                if (summaryText.isNullOrBlank()) {
                    return Result.failure(Exception("要約結果が空です"))
                }

                val originalTokens = estimateTokens(content)
                val summaryTokens = estimateTokens(summaryText)

                val resultConfig = config.copy(
                    originalTokens = originalTokens,
                    summaryTokens = summaryTokens
                )

                Result.success(SummarizeResult(summaryText, originalTokens, summaryTokens, resultConfig))
            } finally {
                summarizer.close()
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun saveSummary(messageId: Long, summaryText: String, config: SummarizeConfig) {
        val configJson = gson.toJson(config)
        messageDao.updateSummaryWithConfig(messageId, summaryText, configJson)
    }

    // ── Error translation ──

    private fun translateMlKitError(e: Exception): Exception {
        val message = e.message ?: return e
        return when {
            message.contains("BUSY", ignoreCase = true) ->
                Exception("モデルがビジーです。しばらくしてから再試行してください。")
            message.contains("BACKGROUND_USE_BLOCKED", ignoreCase = true) ->
                Exception("バックグラウンドでのモデル使用がブロックされました。アプリをフォアグラウンドに戻してください。")
            message.contains("PER_APP_BATTERY_USE_QUOTA_EXCEEDED", ignoreCase = true) ->
                Exception("バッテリー使用量の上限に達しました。しばらく待ってから再試行してください。")
            message.contains("MODEL_NOT_FOUND", ignoreCase = true) ->
                Exception("モデルが見つかりません。設定画面からダウンロードしてください。")
            else -> e
        }
    }

    // ── Helpers ──

    private suspend fun updateConversationTitleIfNeeded(conversationId: Long) {
        val allMessages = messageDao.getMessagesForConversationSync(conversationId)
        val userMessages = allMessages.filter { it.role == "user" }
        val firstUserMessage = userMessages.firstOrNull()?.content
        if (firstUserMessage != null && userMessages.size <= 1) {
            val title = firstUserMessage.take(30) + if (firstUserMessage.length > 30) "..." else ""
            updateConversationTitle(conversationId, title)
        }
    }
}
