package com.example.nanochat.data.local

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "messages",
    foreignKeys = [
        ForeignKey(
            entity = ConversationEntity::class,
            parentColumns = ["id"],
            childColumns = ["conversationId"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [Index("conversationId"), Index("parentMessageId")]
)
data class MessageEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val conversationId: Long,
    val role: String,           // "user" or "assistant"
    val content: String,
    val createdAt: Long = System.currentTimeMillis(),
    val summaryText: String? = null,
    val isSummarized: Boolean = false,
    val summarizeConfigJson: String? = null,
    val isExcluded: Boolean = false,
    val parentMessageId: Long? = null,
    val siblingIndex: Int = 0,
    val activeChildId: Long? = null
)
