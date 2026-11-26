package com.example.feature.inbox

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.example.core.TorveContainer
import com.example.core.database.ThreadEntity
import com.example.core.database.WorkspaceEntity
import com.example.feature.sandbox.RunningServerInfo
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

enum class InboxFilter(val label: String) {
    ALL("All"),
    ACTIVE_SANDBOXES("Active Sandboxes"),
    WEB_PROJECTS("Web Projects"),
    STARRED("Starred")
}

data class InboxThreadItem(
    val thread: ThreadEntity,
    val workspace: WorkspaceEntity?,
    val runningServer: RunningServerInfo?
)

data class InboxUiState(
    val searchQuery: String = "",
    val selectedFilter: InboxFilter = InboxFilter.ALL,
    val items: List<InboxThreadItem> = emptyList(),
    val runningServers: List<RunningServerInfo> = emptyList(),
    val workspaces: List<WorkspaceEntity> = emptyList()
)

class InboxViewModel(private val container: TorveContainer) : ViewModel() {

    private val _searchQuery = MutableStateFlow("")
    private val _selectedFilter = MutableStateFlow(InboxFilter.ALL)

    val uiState: StateFlow<InboxUiState> = combine(
        _searchQuery,
        _selectedFilter,
        container.torveDao.observeActiveThreads(),
        container.torveDao.observeWorkspaces(),
        container.devServerManager.runningServers
    ) { query, filter, threads, workspaces, serversMap ->
        val workspaceMap = workspaces.associateBy { it.id }
        val joined = threads.map { thread ->
            InboxThreadItem(
                thread = thread,
                workspace = workspaceMap[thread.workspaceId],
                runningServer = serversMap[thread.workspaceId]
            )
        }

        val filtered = joined.filter { item ->
            val matchesFilter = when (filter) {
                InboxFilter.ALL -> true
                InboxFilter.ACTIVE_SANDBOXES -> item.runningServer != null
                InboxFilter.WEB_PROJECTS -> item.workspace?.projectType == "web" || item.runningServer != null
                InboxFilter.STARRED -> item.thread.isStarred
            }
            val q = query.trim().lowercase()
            val matchesQuery = q.isEmpty() ||
                item.thread.title.lowercase().contains(q) ||
                item.thread.lastSnippet.lowercase().contains(q) ||
                (item.workspace?.name?.lowercase()?.contains(q) == true) ||
                item.thread.modelId.lowercase().contains(q)

            matchesFilter && matchesQuery
        }

        InboxUiState(
            searchQuery = query,
            selectedFilter = filter,
            items = filtered,
            runningServers = serversMap.values.sortedByDescending { it.startedAtMs },
            workspaces = workspaces
        )
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000),
        initialValue = InboxUiState()
    )

    fun setSearchQuery(query: String) {
        _searchQuery.value = query
    }

    fun setFilter(filter: InboxFilter) {
        _selectedFilter.value = filter
    }

    fun createNewTask(onCreated: (String) -> Unit) {
        viewModelScope.launch {
            val thread = container.createNewThreadAndWorkspace()
            onCreated(thread.id)
        }
    }

    fun toggleStar(threadId: String, currentlyStarred: Boolean) {
        viewModelScope.launch {
            container.torveDao.setThreadStarred(threadId, !currentlyStarred)
        }
    }

    fun archiveThread(threadId: String) {
        viewModelScope.launch {
            container.torveDao.setThreadArchived(threadId, true)
        }
    }

    fun restoreThread(threadId: String) {
        viewModelScope.launch {
            container.torveDao.setThreadArchived(threadId, false)
        }
    }

    fun deleteThreadAndWorkspace(item: InboxThreadItem) {
        viewModelScope.launch {
            container.devServerManager.stopServer(item.thread.workspaceId)
            container.torveDao.deleteThread(item.thread.id)
            container.sandboxBootstrap.deleteWorkspaceFiles(item.thread.workspaceId)
            container.torveDao.deleteWorkspace(item.thread.workspaceId)
        }
    }

    fun stopServer(workspaceId: String) {
        viewModelScope.launch {
            container.devServerManager.stopServer(workspaceId)
            container.torveDao.updateWorkspaceServer(workspaceId, null, null)
        }
    }

    fun refreshAccount() {
        viewModelScope.launch {
            container.authRepository.hydrateSession()
        }
    }

    class Factory(private val container: TorveContainer) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            return InboxViewModel(container) as T
        }
    }
}
