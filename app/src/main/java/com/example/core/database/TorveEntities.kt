package com.example.core.database

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(tableName = "workspaces")
data class WorkspaceEntity(
    @PrimaryKey val id: String,
    val name: String,
    val directoryPath: String,
    val projectType: String = "general", // "web" | "script" | "general"
    val activeServerPort: Int? = null,
    val activeServerCommand: String? = null,
    val createdAt: Long = System.currentTimeMillis(),
    val lastAccessedAt: Long = System.currentTimeMillis()
)

@Entity(
    tableName = "threads",
    foreignKeys = [
        ForeignKey(
            entity = WorkspaceEntity::class,
            parentColumns = ["id"],
            childColumns = ["workspaceId"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [Index("workspaceId"), Index("updatedAt")]
)
data class ThreadEntity(
    @PrimaryKey val id: String,
    val workspaceId: String,
    val title: String,
    val lastSnippet: String = "",
    val modelId: String = "",
    val isStarred: Boolean = false,
    val isArchived: Boolean = false,
    val totalTokensUsed: Long = 0L,
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis()
)

@Entity(
    tableName = "messages",
    foreignKeys = [
        ForeignKey(
            entity = ThreadEntity::class,
            parentColumns = ["id"],
            childColumns = ["threadId"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [Index("threadId"), Index("createdAt")]
)
data class MessageEntity(
    @PrimaryKey val id: String,
    val threadId: String,
    val role: String, // "user" | "assistant" | "tool" | "system"
    val content: String = "",
    val reasoningContent: String? = null,
    val toolCallId: String? = null,
    val toolCallsJson: String? = null,
    val promptTokens: Int = 0,
    val completionTokens: Int = 0,
    val isStreaming: Boolean = false,
    val createdAt: Long = System.currentTimeMillis()
)

@Entity(
    tableName = "tool_call_logs",
    foreignKeys = [
        ForeignKey(
            entity = ThreadEntity::class,
            parentColumns = ["id"],
            childColumns = ["threadId"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [Index("threadId"), Index("messageId"), Index("createdAt")]
)
data class ToolCallLogEntity(
    @PrimaryKey val id: String,
    val threadId: String,
    val messageId: String,
    val toolCallId: String,
    val toolName: String,
    val argumentsJson: String,
    val summary: String = "",
    val output: String = "",
    val diffContent: String? = null,
    val status: String = ToolCallStatus.RUNNING,
    val durationMs: Long = 0L,
    val createdAt: Long = System.currentTimeMillis()
)

object ToolCallStatus {
    const val PENDING_APPROVAL = "PENDING_APPROVAL"
    const val RUNNING = "RUNNING"
    const val SUCCESS = "SUCCESS"
    const val ERROR = "ERROR"
    const val DENIED = "DENIED"
}
