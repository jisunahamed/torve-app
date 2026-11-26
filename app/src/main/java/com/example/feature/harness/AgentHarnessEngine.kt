package com.example.feature.harness

import com.example.core.database.AppPreferencesStore
import com.example.core.database.MessageEntity
import com.example.core.database.ToolCallLogEntity
import com.example.core.database.ToolCallStatus
import com.example.core.database.TorveDao
import com.example.core.network.ChatMessageDto
import com.example.core.network.InferenceStreamEvent
import com.example.core.network.ToolCallDto
import com.example.core.network.TorveInferenceClient
import com.example.core.network.TorveModel
import com.example.core.security.SecureTokenStore
import com.example.feature.sandbox.LocalDevServerManager
import com.example.feature.sandbox.SandboxBootstrap
import com.example.feature.skills.AgentSkill
import com.example.feature.skills.SkillRegistry
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import java.io.File
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean

data class PendingShellApproval(
    val toolLogId: String,
    val threadId: String,
    val command: String,
    val workspacePath: String
)

data class HarnessRunState(
    val activeThreadId: String? = null,
    val isRunning: Boolean = false,
    val currentStep: Int = 0,
    val maxSteps: Int = 20,
    val stepLimitReached: Boolean = false,
    val activeSkillName: String? = null,
    val pendingApproval: PendingShellApproval? = null,
    val errorMessage: String? = null
)

/**
 * Native Kotlin Coroutines + StateFlow ReAct / Tool-Calling Agent Harness Engine.
 * Supports progressive SKILL.md catalog disclosure, explicit slash-command skill injection,
 * incremental multi-call SSE delta assembly, live streaming bash_exec cards, and approval gates.
 */
class AgentHarnessEngine(
    private val torveDao: TorveDao,
    private val tokenStore: SecureTokenStore,
    private val preferencesStore: AppPreferencesStore,
    private val inferenceClient: TorveInferenceClient,
    private val sandboxBootstrap: SandboxBootstrap,
    private val devServerManager: LocalDevServerManager,
    private val skillRegistry: SkillRegistry,
    private val toolRegistry: HarnessToolRegistry,
    private val json: Json
) {
    private val engineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var activeJob: Job? = null
    private val approvalGates = ConcurrentHashMap<String, CompletableDeferred<Boolean>>()

    private val _runState = MutableStateFlow(HarnessRunState())
    val runState: StateFlow<HarnessRunState> = _runState.asStateFlow()

    fun sendUserPrompt(
        threadId: String,
        userText: String,
        model: TorveModel?,
        modelId: String
    ) {
        val trimmed = userText.trim()
        if (trimmed.isEmpty()) return
        stopGeneration()

        activeJob = engineScope.launch {
            val thread = torveDao.getThreadById(threadId) ?: return@launch
            val workspaceDir = sandboxBootstrap.ensureWorkspaceDir(thread.workspaceId)
            skillRegistry.syncDisabledStateAndRefresh(workspaceDir)

            // Detect explicit slash-command skill invocation (e.g. "/web build a portfolio")
            val slashMatch = skillRegistry.matchSlashCommand(trimmed, workspaceDir)
            val explicitSkill = slashMatch?.first

            if (thread.title == "New Task" || thread.title.isBlank()) {
                val titleSource = slashMatch?.second?.ifBlank { trimmed } ?: trimmed
                val autoTitle = titleSource.lines().first().take(48).trim()
                if (autoTitle.isNotBlank()) {
                    torveDao.updateThreadTitle(threadId, autoTitle)
                }
            }

            val userMsg = MessageEntity(
                id = UUID.randomUUID().toString(),
                threadId = threadId,
                role = "user",
                content = trimmed,
                createdAt = System.currentTimeMillis()
            )
            torveDao.upsertMessage(userMsg)
            torveDao.updateThreadActivity(threadId, trimmed.take(120), 0L)

            runAgentLoop(
                threadId = threadId,
                model = model,
                modelId = modelId,
                explicitSkill = explicitSkill
            )
        }
    }

    fun continueFromStepLimit(
        threadId: String,
        model: TorveModel?,
        modelId: String
    ) {
        stopGeneration()
        activeJob = engineScope.launch {
            runAgentLoop(
                threadId = threadId,
                model = model,
                modelId = modelId,
                explicitSkill = null
            )
        }
    }

    fun respondToApproval(toolLogId: String, approved: Boolean) {
        approvalGates.remove(toolLogId)?.complete(approved)
        if (_runState.value.pendingApproval?.toolLogId == toolLogId) {
            _runState.value = _runState.value.copy(pendingApproval = null)
        }
    }

    fun stopGeneration() {
        inferenceClient.cancelActiveStream()
        approvalGates.values.forEach { it.complete(false) }
        approvalGates.clear()
        activeJob?.cancel()
        activeJob = null
        _runState.value = _runState.value.copy(
            isRunning = false,
            pendingApproval = null
        )
    }

    fun clearError() {
        _runState.value = _runState.value.copy(errorMessage = null)
    }

    private suspend fun runAgentLoop(
        threadId: String,
        model: TorveModel?,
        modelId: String,
        explicitSkill: AgentSkill?
    ) {
        val apiKey = tokenStore.getApiKey()
        if (apiKey.isNullOrBlank()) {
            _runState.value = HarnessRunState(
                activeThreadId = threadId,
                isRunning = false,
                errorMessage = "Not signed in to Torve AI. Please authenticate first."
            )
            return
        }

        val thread = torveDao.getThreadById(threadId) ?: return
        val workspace = torveDao.getWorkspaceById(thread.workspaceId) ?: return
        val workspaceDir = sandboxBootstrap.ensureWorkspaceDir(workspace.id)
        skillRegistry.syncDisabledStateAndRefresh(workspaceDir)

        val settings = preferencesStore.settingsFlow.first()
        val maxSteps = settings.maxAgentSteps.coerceIn(5, 40)
        val allowTools = model?.supportsTools ?: true
        val tools = if (allowTools) toolRegistry.getToolDefinitions() else null

        var activeSkill: AgentSkill? = explicitSkill
        _runState.value = HarnessRunState(
            activeThreadId = threadId,
            isRunning = true,
            currentStep = 0,
            maxSteps = maxSteps,
            stepLimitReached = false,
            activeSkillName = explicitSkill?.name,
            errorMessage = null
        )

        var step = 0
        try {
            while (step < maxSteps) {
                step++
                _runState.value = _runState.value.copy(currentStep = step)

                val history = torveDao.getMessagesSnapshot(threadId)
                val requestMessages = assembleContextMessages(
                    workspaceName = workspace.name,
                    workspaceDir = workspaceDir,
                    runningServerPort = devServerManager.getServerForWorkspace(workspace.id)?.port,
                    history = history,
                    allowTools = allowTools,
                    injectedSkill = activeSkill
                )

                val assistantMsgId = UUID.randomUUID().toString()
                val assistantCreatedAt = System.currentTimeMillis()
                torveDao.upsertMessage(
                    MessageEntity(
                        id = assistantMsgId,
                        threadId = threadId,
                        role = "assistant",
                        content = "",
                        reasoningContent = null,
                        isStreaming = true,
                        createdAt = assistantCreatedAt
                    )
                )

                val contentBuilder = StringBuilder()
                val reasoningBuilder = StringBuilder()
                val toolAccumulator = SseToolCallAccumulator()
                var promptTokens = 0
                var completionTokens = 0
                var streamError: String? = null
                var lastPersistAt = 0L

                inferenceClient.streamChatCompletion(
                    apiKey = apiKey,
                    model = model,
                    modelId = modelId,
                    messages = requestMessages,
                    tools = tools
                ).collect { event ->
                    when (event) {
                        is InferenceStreamEvent.ReasoningDelta -> {
                            reasoningBuilder.append(event.text)
                            val now = System.currentTimeMillis()
                            if (now - lastPersistAt >= 80L) {
                                lastPersistAt = now
                                torveDao.updateMessageContent(
                                    messageId = assistantMsgId,
                                    content = contentBuilder.toString(),
                                    reasoning = reasoningBuilder.toString(),
                                    toolCallsJson = null,
                                    promptTokens = promptTokens,
                                    completionTokens = completionTokens,
                                    isStreaming = true
                                )
                            }
                        }

                        is InferenceStreamEvent.ContentDelta -> {
                            contentBuilder.append(event.text)
                            val now = System.currentTimeMillis()
                            if (now - lastPersistAt >= 80L) {
                                lastPersistAt = now
                                torveDao.updateMessageContent(
                                    messageId = assistantMsgId,
                                    content = contentBuilder.toString(),
                                    reasoning = reasoningBuilder.toString().ifBlank { null },
                                    toolCallsJson = null,
                                    promptTokens = promptTokens,
                                    completionTokens = completionTokens,
                                    isStreaming = true
                                )
                            }
                        }

                        is InferenceStreamEvent.ToolCallDelta -> {
                            toolAccumulator.appendDelta(
                                index = event.index,
                                id = event.id,
                                nameChunk = event.name,
                                argumentsChunk = event.argumentsChunk
                            )
                        }

                        is InferenceStreamEvent.UsageUpdate -> {
                            promptTokens = event.promptTokens
                            completionTokens = event.completionTokens
                        }

                        is InferenceStreamEvent.RateLimitRetry -> Unit

                        is InferenceStreamEvent.Error -> {
                            streamError = event.message
                        }

                        is InferenceStreamEvent.Done -> Unit
                    }
                }

                val finalizedToolCalls = toolAccumulator.buildFinalizedToolCalls(stepNumber = step)
                val toolCallsJson = if (finalizedToolCalls.isNotEmpty()) {
                    json.encodeToString(ListSerializer(ToolCallDto.serializer()), finalizedToolCalls)
                } else null

                val finalContent = contentBuilder.toString()
                val finalReasoning = reasoningBuilder.toString().ifBlank { null }

                torveDao.updateMessageContent(
                    messageId = assistantMsgId,
                    content = finalContent,
                    reasoning = finalReasoning,
                    toolCallsJson = toolCallsJson,
                    promptTokens = promptTokens,
                    completionTokens = completionTokens,
                    isStreaming = false
                )

                val totalTurnTokens = (promptTokens + completionTokens).toLong()
                val snippet = finalContent.lines().firstOrNull { it.isNotBlank() }?.take(120)
                    ?: finalizedToolCalls.firstOrNull()?.let { "Ran ${it.function.name}" }
                    ?: "Completed turn"
                torveDao.updateThreadActivity(threadId, snippet, totalTurnTokens)

                if (streamError != null) {
                    _runState.value = _runState.value.copy(
                        isRunning = false,
                        errorMessage = streamError
                    )
                    return
                }

                // If no tool calls were requested (or model is in Chat-Only Mode), end turn cleanly
                if (finalizedToolCalls.isEmpty() || !allowTools) {
                    _runState.value = _runState.value.copy(isRunning = false, stepLimitReached = false)
                    return
                }

                // Execute every tool call in this turn and append a matching role="tool" message for each
                val askBeforeShell = preferencesStore.settingsFlow.first().askBeforeShellCommands
                for (tc in finalizedToolCalls) {
                    val toolLogId = UUID.randomUUID().toString()
                    val summary = toolRegistry.summarizeArguments(tc.function.name, tc.function.arguments)

                    val needsApproval = askBeforeShell && tc.function.name == "bash_exec"
                    torveDao.upsertToolCallLog(
                        ToolCallLogEntity(
                            id = toolLogId,
                            threadId = threadId,
                            messageId = assistantMsgId,
                            toolCallId = tc.id,
                            toolName = tc.function.name,
                            argumentsJson = tc.function.arguments,
                            summary = summary,
                            status = if (needsApproval) ToolCallStatus.PENDING_APPROVAL else ToolCallStatus.RUNNING,
                            createdAt = System.currentTimeMillis()
                        )
                    )

                    if (needsApproval) {
                        val gate = CompletableDeferred<Boolean>()
                        approvalGates[toolLogId] = gate
                        _runState.value = _runState.value.copy(
                            pendingApproval = PendingShellApproval(
                                toolLogId = toolLogId,
                                threadId = threadId,
                                command = summary,
                                workspacePath = workspaceDir.name
                            )
                        )
                        val approved = gate.await()
                        if (!approved) {
                            val deniedMsg = """{"error":"Shell command execution was denied by the user."}"""
                            torveDao.updateToolCallResult(
                                logId = toolLogId,
                                output = deniedMsg,
                                diffContent = null,
                                status = ToolCallStatus.DENIED,
                                durationMs = 0L
                            )
                            torveDao.upsertMessage(
                                MessageEntity(
                                    id = UUID.randomUUID().toString(),
                                    threadId = threadId,
                                    role = "tool",
                                    content = deniedMsg,
                                    toolCallId = tc.id,
                                    createdAt = System.currentTimeMillis()
                                )
                            )
                            continue
                        }
                        torveDao.updateToolCallResult(
                            logId = toolLogId,
                            output = "Executing...",
                            diffContent = null,
                            status = ToolCallStatus.RUNNING,
                            durationMs = 0L
                        )
                    }

                    // Serialize live stdout/stderr streaming updates so they never race final status
                    val logUpdateMutex = Mutex()
                    val toolFinished = AtomicBoolean(false)

                    val result = toolRegistry.executeTool(
                        workspaceId = workspace.id,
                        workspaceName = workspace.name,
                        workspaceDir = workspaceDir,
                        toolName = tc.function.name,
                        argumentsJson = tc.function.arguments,
                        onLiveOutput = { liveChunk ->
                            if (!toolFinished.get()) {
                                engineScope.launch {
                                    logUpdateMutex.withLock {
                                        if (!toolFinished.get()) {
                                            torveDao.updateToolCallResult(
                                                logId = toolLogId,
                                                output = liveChunk,
                                                diffContent = null,
                                                status = ToolCallStatus.RUNNING,
                                                durationMs = 0L
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    )

                    toolFinished.set(true)
                    logUpdateMutex.withLock {
                        torveDao.updateToolCallResult(
                            logId = toolLogId,
                            output = result.output,
                            diffContent = result.diffContent,
                            status = if (result.isError) ToolCallStatus.ERROR else ToolCallStatus.SUCCESS,
                            durationMs = result.durationMs
                        )
                    }

                    if (!result.loadedSkillName.isNullOrBlank()) {
                        _runState.value = _runState.value.copy(activeSkillName = result.loadedSkillName)
                        activeSkill = skillRegistry.loadSkillByName(result.loadedSkillName, workspaceDir) ?: activeSkill
                    }

                    torveDao.upsertMessage(
                        MessageEntity(
                            id = UUID.randomUUID().toString(),
                            threadId = threadId,
                            role = "tool",
                            content = result.output,
                            toolCallId = tc.id,
                            createdAt = System.currentTimeMillis()
                        )
                    )
                }
            }

            // Reached configured max steps
            _runState.value = _runState.value.copy(
                isRunning = false,
                stepLimitReached = true
            )
        } catch (_: CancellationException) {
            val latest = torveDao.getMessagesSnapshot(threadId).lastOrNull()
            if (latest != null && latest.isStreaming) {
                torveDao.updateMessageContent(
                    messageId = latest.id,
                    content = latest.content,
                    reasoning = latest.reasoningContent,
                    toolCallsJson = latest.toolCallsJson,
                    promptTokens = latest.promptTokens,
                    completionTokens = latest.completionTokens,
                    isStreaming = false
                )
            }
            _runState.value = _runState.value.copy(isRunning = false, pendingApproval = null)
        } catch (e: Exception) {
            _runState.value = _runState.value.copy(
                isRunning = false,
                pendingApproval = null,
                errorMessage = e.localizedMessage ?: "Agent harness encountered an unexpected error."
            )
        }
    }

    private fun assembleContextMessages(
        workspaceName: String,
        workspaceDir: File,
        runningServerPort: Int?,
        history: List<MessageEntity>,
        allowTools: Boolean,
        injectedSkill: AgentSkill?
    ): List<ChatMessageDto> {
        val workspaceTree = toolRegistry.buildWorkspaceTreeSnapshot(workspaceDir)
        val skillsCatalog = skillRegistry.buildCatalogPromptSection(workspaceDir)
        val serverNote = if (runningServerPort != null) {
            "Active local dev server: http://127.0.0.1:$runningServerPort"
        } else {
            "No local dev server currently running."
        }
        val systemPrompt = buildString {
            appendLine("You are Torve AI, an expert on-device agentic software engineering harness running on Android.")
            appendLine("Active workspace: $workspaceName (${workspaceDir.absolutePath})")
            appendLine(serverNote)
            appendLine("Current workspace files:")
            appendLine(workspaceTree)
            if (allowTools) {
                appendLine()
                appendLine("You have access to 8 sandboxed tools: bash_exec, read_file, write_file, edit_file, list_files, grep_search, manage_dev_server, load_skill.")
                appendLine("When building web apps or sites:")
                appendLine("- Write clean, self-contained responsive HTML/CSS/JS files into the workspace using write_file.")
                appendLine("- Call manage_dev_server(action='start', port=8080) to start the local loopback server so the user can open Live Preview immediately.")
                appendLine("- Keep responses concise, structured, and helpful.")
            } else {
                appendLine()
                appendLine("This model is currently running in Chat-Only Mode (tool calling is disabled for this model).")
            }

            if (skillsCatalog.isNotBlank()) {
                appendLine()
                appendLine(skillsCatalog)
            }

            if (injectedSkill != null) {
                appendLine()
                appendLine("=== ACTIVE SKILL INSTRUCTIONS: ${injectedSkill.name} ===")
                appendLine("Description: ${injectedSkill.description}")
                if (injectedSkill.allowedTools.isNotEmpty()) {
                    appendLine("Preferred tools: ${injectedSkill.allowedTools.joinToString(", ")}")
                }
                appendLine(injectedSkill.instructionsBody)
                appendLine("=== END ACTIVE SKILL ===")
            }
        }

        val result = mutableListOf(ChatMessageDto(role = "system", content = systemPrompt))
        for (msg in history.takeLast(40)) {
            when (msg.role) {
                "user" -> result.add(ChatMessageDto(role = "user", content = msg.content))
                "assistant" -> {
                    val toolCalls = msg.toolCallsJson?.let { raw ->
                        runCatching { json.decodeFromString(ListSerializer(ToolCallDto.serializer()), raw) }.getOrNull()
                    }
                    result.add(
                        ChatMessageDto(
                            role = "assistant",
                            content = msg.content.ifBlank { null },
                            toolCalls = toolCalls
                        )
                    )
                }
                "tool" -> {
                    if (!msg.toolCallId.isNullOrBlank()) {
                        result.add(
                            ChatMessageDto(
                                role = "tool",
                                content = msg.content,
                                toolCallId = msg.toolCallId
                            )
                        )
                    }
                }
            }
        }
        return result
    }
}
