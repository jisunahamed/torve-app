package com.example.core.database

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

@Dao
interface TorveDao {

    // Workspaces
    @Query("SELECT * FROM workspaces ORDER BY lastAccessedAt DESC")
    fun observeWorkspaces(): Flow<List<WorkspaceEntity>>

    @Query("SELECT * FROM workspaces WHERE id = :workspaceId LIMIT 1")
    suspend fun getWorkspaceById(workspaceId: String): WorkspaceEntity?

    @Query("SELECT * FROM workspaces WHERE id = :workspaceId LIMIT 1")
    fun observeWorkspace(workspaceId: String): Flow<WorkspaceEntity?>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertWorkspace(workspace: WorkspaceEntity)

    @Query("UPDATE workspaces SET activeServerPort = :port, activeServerCommand = :command, lastAccessedAt = :now WHERE id = :workspaceId")
    suspend fun updateWorkspaceServer(workspaceId: String, port: Int?, command: String?, now: Long = System.currentTimeMillis())

    @Query("UPDATE workspaces SET projectType = :projectType, lastAccessedAt = :now WHERE id = :workspaceId")
    suspend fun updateWorkspaceProjectType(workspaceId: String, projectType: String, now: Long = System.currentTimeMillis())

    @Query("DELETE FROM workspaces WHERE id = :workspaceId")
    suspend fun deleteWorkspace(workspaceId: String)

    // Threads
    @Query("SELECT * FROM threads WHERE isArchived = 0 ORDER BY updatedAt DESC")
    fun observeActiveThreads(): Flow<List<ThreadEntity>>

    @Query("SELECT * FROM threads WHERE id = :threadId LIMIT 1")
    fun observeThread(threadId: String): Flow<ThreadEntity?>

    @Query("SELECT * FROM threads WHERE id = :threadId LIMIT 1")
    suspend fun getThreadById(threadId: String): ThreadEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertThread(thread: ThreadEntity)

    @Update
    suspend fun updateThread(thread: ThreadEntity)

    @Query("UPDATE threads SET isStarred = :starred WHERE id = :threadId")
    suspend fun setThreadStarred(threadId: String, starred: Boolean)

    @Query("UPDATE threads SET isArchived = :archived WHERE id = :threadId")
    suspend fun setThreadArchived(threadId: String, archived: Boolean)

    @Query("UPDATE threads SET modelId = :modelId, updatedAt = :now WHERE id = :threadId")
    suspend fun updateThreadModel(threadId: String, modelId: String, now: Long = System.currentTimeMillis())

    @Query("UPDATE threads SET title = :title, updatedAt = :now WHERE id = :threadId")
    suspend fun updateThreadTitle(threadId: String, title: String, now: Long = System.currentTimeMillis())

    @Query("UPDATE threads SET lastSnippet = :snippet, updatedAt = :now, totalTokensUsed = totalTokensUsed + :addedTokens WHERE id = :threadId")
    suspend fun updateThreadActivity(threadId: String, snippet: String, addedTokens: Long, now: Long = System.currentTimeMillis())

    @Query("DELETE FROM threads WHERE id = :threadId")
    suspend fun deleteThread(threadId: String)

    // Messages
    @Query("SELECT * FROM messages WHERE threadId = :threadId ORDER BY createdAt ASC")
    fun observeMessages(threadId: String): Flow<List<MessageEntity>>

    @Query("SELECT * FROM messages WHERE threadId = :threadId ORDER BY createdAt ASC")
    suspend fun getMessagesSnapshot(threadId: String): List<MessageEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertMessage(message: MessageEntity)

    @Query("UPDATE messages SET content = :content, reasoningContent = :reasoning, toolCallsJson = :toolCallsJson, promptTokens = :promptTokens, completionTokens = :completionTokens, isStreaming = :isStreaming WHERE id = :messageId")
    suspend fun updateMessageContent(
        messageId: String,
        content: String,
        reasoning: String?,
        toolCallsJson: String?,
        promptTokens: Int,
        completionTokens: Int,
        isStreaming: Boolean
    )

    // Tool Call Logs
    @Query("SELECT * FROM tool_call_logs WHERE threadId = :threadId ORDER BY createdAt ASC")
    fun observeToolCallLogs(threadId: String): Flow<List<ToolCallLogEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertToolCallLog(log: ToolCallLogEntity)

    @Query("UPDATE tool_call_logs SET output = :output, diffContent = :diffContent, status = :status, durationMs = :durationMs WHERE id = :logId")
    suspend fun updateToolCallResult(
        logId: String,
        output: String,
        diffContent: String?,
        status: String,
        durationMs: Long
    )
}
