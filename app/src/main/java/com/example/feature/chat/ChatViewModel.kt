package com.example.feature.chat

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.example.core.TorveContainer
import com.example.core.database.MessageEntity
import com.example.core.database.ThreadEntity
import com.example.core.database.ToolCallLogEntity
import com.example.core.database.WorkspaceEntity
import com.example.core.network.RateLimitBannerState
import com.example.core.network.TorveModel
import com.example.feature.harness.HarnessRunState
import com.example.feature.sandbox.RunningServerInfo
import com.example.feature.skills.AgentSkill
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class ChatUiState(
    val thread: ThreadEntity? = null,
    val workspace: WorkspaceEntity? = null,
    val messages: List<MessageEntity> = emptyList(),
    val toolLogsByMessageId: Map<String, List<ToolCallLogEntity>> = emptyMap(),
    val models: List<TorveModel> = emptyList(),
    val selectedModel: TorveModel? = null,
    val selectedModelId: String = "",
    val supportsTools: Boolean = true,
    val enabledSkills: List<AgentSkill> = emptyList(),
    val runState: HarnessRunState = HarnessRunState(),
    val rateLimitBanner: RateLimitBannerState? = null,
    val runningServer: RunningServerInfo? = null,
    val attachedNotice: String? = null
)

class ChatViewModel(
    private val threadId: String,
    private val container: TorveContainer
) : ViewModel() {

    private val _attachedNotice = MutableStateFlow<String?>(null)

    private val threadBundle = combine(
        container.torveDao.observeThread(threadId),
        container.torveDao.observeMessages(threadId),
        container.torveDao.observeToolCallLogs(threadId),
        container.torveDao.observeWorkspaces()
    ) { thread, messages, toolLogs, workspaces ->
        val ws = thread?.let { t -> workspaces.firstOrNull { it.id == t.workspaceId } }
        ThreadDataBundle(thread, ws, messages, toolLogs)
    }

    private val runtimeBundle = combine(
        container.authRepository.sessionState,
        container.agentHarnessEngine.runState,
        container.rateLimitQueue.bannerState,
        container.devServerManager.runningServers,
        _attachedNotice
    ) { session, runState, rateLimit, servers, notice ->
        RuntimeDataBundle(session.models, runState, rateLimit, servers, notice)
    }

    val uiState: StateFlow<ChatUiState> = combine(
        threadBundle,
        runtimeBundle,
        container.skillRegistry.skills
    ) { tData, rData, allSkills ->
        val thread = tData.thread
        val resolvedModelId = thread?.modelId?.takeIf { it.isNotBlank() }
            ?: rData.models.firstOrNull { it.supportsTools }?.id
            ?: rData.models.firstOrNull()?.id
            ?: ""
        val selectedModel = rData.models.firstOrNull { it.id == resolvedModelId }
        val activeRun = if (rData.runState.activeThreadId == threadId) rData.runState else HarnessRunState()
        val server = thread?.let { rData.servers[it.workspaceId] }

        ChatUiState(
            thread = thread,
            workspace = tData.workspace,
            messages = tData.messages.filter { it.role != "tool" && it.role != "system" },
            toolLogsByMessageId = tData.toolLogs.groupBy { it.messageId },
            models = rData.models,
            selectedModel = selectedModel,
            selectedModelId = resolvedModelId,
            supportsTools = selectedModel?.supportsTools ?: true,
            enabledSkills = allSkills.filter { it.enabled },
            runState = activeRun,
            rateLimitBanner = rData.rateLimit,
            runningServer = server,
            attachedNotice = rData.notice
        )
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000),
        initialValue = ChatUiState()
    )

    init {
        viewModelScope.launch {
            withContext(Dispatchers.IO) {
                val thread = container.torveDao.getThreadById(threadId)
                val wsDir = thread?.let { container.sandboxBootstrap.ensureWorkspaceDir(it.workspaceId) }
                container.skillRegistry.syncDisabledStateAndRefresh(wsDir)
            }
            if (container.authRepository.sessionState.value.models.isEmpty()) {
                container.authRepository.hydrateSession()
            }
        }
    }

    fun sendMessage(prompt: String) {
        val state = uiState.value
        val modelId = state.selectedModelId.ifBlank {
            state.models.firstOrNull()?.id ?: return
        }
        container.agentHarnessEngine.sendUserPrompt(
            threadId = threadId,
            userText = prompt,
            model = state.selectedModel,
            modelId = modelId
        )
    }

    fun selectModel(model: TorveModel) {
        viewModelScope.launch {
            container.torveDao.updateThreadModel(threadId, model.id)
            container.preferencesStore.setDefaultModelId(model.id)
        }
    }

    fun stopGeneration() {
        container.agentHarnessEngine.stopGeneration()
    }

    fun continueStepLimit() {
        val state = uiState.value
        val modelId = state.selectedModelId.ifBlank { return }
        container.agentHarnessEngine.continueFromStepLimit(
            threadId = threadId,
            model = state.selectedModel,
            modelId = modelId
        )
    }

    fun respondToShellApproval(toolLogId: String, approved: Boolean) {
        container.agentHarnessEngine.respondToApproval(toolLogId, approved)
    }

    fun clearError() {
        container.agentHarnessEngine.clearError()
    }

    fun clearAttachedNotice() {
        _attachedNotice.value = null
    }

    /**
     * Copies a user-selected document via zero-permission Android SAF into the isolated workspace.
     */
    fun importFileToWorkspace(context: Context, uri: Uri) {
        viewModelScope.launch {
            val workspaceId = uiState.value.thread?.workspaceId ?: return@launch
            val workspaceDir = container.sandboxBootstrap.ensureWorkspaceDir(workspaceId)
            val savedName = withContext(Dispatchers.IO) {
                val displayName = resolveDisplayName(context, uri)
                    .replace(Regex("[^a-zA-Z0-9._-]"), "_")
                    .ifBlank { "attached_${System.currentTimeMillis()}" }
                val dest = container.sandboxBootstrap.resolveJailedPath(workspaceDir, "attachments/$displayName")
                dest.parentFile?.mkdirs()
                context.contentResolver.openInputStream(uri)?.use { input ->
                    dest.outputStream().use { output -> input.copyTo(output) }
                }
                "attachments/$displayName"
            }
            _attachedNotice.value = "Imported into workspace: $savedName"
        }
    }

    private fun resolveDisplayName(context: Context, uri: Uri): String {
        context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
            if (cursor.moveToFirst()) {
                val idx = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                if (idx >= 0) return cursor.getString(idx) ?: "file"
            }
        }
        return uri.lastPathSegment?.substringAfterLast('/') ?: "file"
    }

    private data class ThreadDataBundle(
        val thread: ThreadEntity?,
        val workspace: WorkspaceEntity?,
        val messages: List<MessageEntity>,
        val toolLogs: List<ToolCallLogEntity>
    )

    private data class RuntimeDataBundle(
        val models: List<TorveModel>,
        val runState: HarnessRunState,
        val rateLimit: RateLimitBannerState?,
        val servers: Map<String, RunningServerInfo>,
        val notice: String?
    )

    class Factory(
        private val threadId: String,
        private val container: TorveContainer
    ) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            return ChatViewModel(threadId, container) as T
        }
    }
}
