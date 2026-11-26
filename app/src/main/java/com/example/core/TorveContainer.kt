package com.example.core

import android.content.Context
import com.example.core.database.AppPreferencesStore
import com.example.core.database.ThreadEntity
import com.example.core.database.TorveDao
import com.example.core.database.TorveDatabase
import com.example.core.database.WorkspaceEntity
import com.example.core.network.RateLimitQueue
import com.example.core.network.TorveApiClient
import com.example.core.network.TorveInferenceClient
import com.example.core.security.PkceManager
import com.example.core.security.SecureTokenStore
import com.example.feature.auth.AuthRepository
import com.example.feature.harness.AgentHarnessEngine
import com.example.feature.harness.HarnessToolRegistry
import com.example.feature.sandbox.LocalDevServerManager
import com.example.feature.sandbox.SandboxBootstrap
import com.example.feature.sandbox.SandboxForegroundService
import kotlinx.coroutines.flow.first
import java.util.UUID

/**
 * Clean constructor-injected application service graph.
 */
class TorveContainer(context: Context) {
    private val appContext = context.applicationContext
    val json = TorveApiClient.defaultTorveJson()

    val pkceManager = PkceManager()
    val tokenStore = SecureTokenStore(appContext)
    val rateLimitQueue = RateLimitQueue()
    val apiClient = TorveApiClient(json = json)
    val inferenceClient = TorveInferenceClient(rateLimitQueue = rateLimitQueue, json = json)

    val database = TorveDatabase.getInstance(appContext)
    val torveDao: TorveDao = database.torveDao()
    val preferencesStore = AppPreferencesStore(appContext)

    val sandboxBootstrap = SandboxBootstrap(appContext).also { it.ensureBootstrapped() }
    val devServerManager = LocalDevServerManager(
        sandboxBootstrap = sandboxBootstrap,
        onServerCountChanged = { count ->
            SandboxForegroundService.syncRunningState(appContext, count)
        }
    )

    val toolRegistry = HarnessToolRegistry(
        sandboxBootstrap = sandboxBootstrap,
        devServerManager = devServerManager,
        torveDao = torveDao,
        json = json
    )

    val agentHarnessEngine = AgentHarnessEngine(
        torveDao = torveDao,
        tokenStore = tokenStore,
        preferencesStore = preferencesStore,
        inferenceClient = inferenceClient,
        sandboxBootstrap = sandboxBootstrap,
        devServerManager = devServerManager,
        toolRegistry = toolRegistry,
        json = json
    )

    val authRepository = AuthRepository(
        pkceManager = pkceManager,
        tokenStore = tokenStore,
        apiClient = apiClient,
        preferencesStore = preferencesStore,
        rateLimitQueue = rateLimitQueue
    )

    suspend fun createNewThreadAndWorkspace(
        title: String = "New Task",
        modelId: String? = null
    ): ThreadEntity {
        val workspaceId = "ws_${UUID.randomUUID().toString().take(8)}"
        val dir = sandboxBootstrap.ensureWorkspaceDir(workspaceId)
        val now = System.currentTimeMillis()
        val workspace = WorkspaceEntity(
            id = workspaceId,
            name = if (title == "New Task") "workspace-${workspaceId.takeLast(4)}" else title.take(32),
            directoryPath = dir.absolutePath,
            projectType = "general",
            createdAt = now,
            lastAccessedAt = now
        )
        torveDao.upsertWorkspace(workspace)

        val resolvedModel = modelId
            ?: preferencesStore.settingsFlow.first().defaultModelId.takeIf { it.isNotBlank() }
            ?: authRepository.sessionState.value.models.firstOrNull()?.id
            ?: ""

        val thread = ThreadEntity(
            id = UUID.randomUUID().toString(),
            workspaceId = workspaceId,
            title = title,
            lastSnippet = "Workspace ready • $workspaceId",
            modelId = resolvedModel,
            createdAt = now,
            updatedAt = now
        )
        torveDao.upsertThread(thread)
        return thread
    }
}
