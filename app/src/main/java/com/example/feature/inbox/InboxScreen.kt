package com.example.feature.inbox

import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Inbox
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.QueryStats
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.Terminal
import androidx.compose.material.icons.outlined.Archive
import androidx.compose.material.icons.outlined.Dns
import androidx.compose.material.icons.outlined.StarBorder
import androidx.compose.material3.Badge
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.ModalDrawerSheet
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.NavigationDrawerItem
import androidx.compose.material3.NavigationDrawerItemDefaults
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Surface
import androidx.compose.material3.SwipeToDismissBox
import androidx.compose.material3.SwipeToDismissBoxValue
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberDrawerState
import androidx.compose.material3.rememberSwipeToDismissBoxState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.R
import com.example.feature.auth.AccountSessionState
import com.example.ui.theme.JetBrainsMonoFamily
import com.example.ui.theme.TorveAmber
import com.example.ui.theme.TorveCyan
import com.example.ui.theme.TorveEmerald
import com.example.ui.theme.TorveIndigo
import kotlinx.coroutines.launch
import java.text.NumberFormat
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun InboxScreen(
    viewModel: InboxViewModel,
    sessionState: AccountSessionState,
    onOpenThread: (String) -> Unit,
    onOpenWorkspaceExplorer: (String) -> Unit,
    onOpenLivePreview: (String, Int) -> Unit,
    onOpenUsage: () -> Unit,
    onOpenSettings: () -> Unit,
    onSignOut: () -> Unit
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val drawerState = rememberDrawerState(initialValue = DrawerValue.Closed)
    val scope = rememberCoroutineScope()
    val snackbarHostState = remember { SnackbarHostState() }
    var showAccountSheet by remember { mutableStateOf(false) }
    var showServersSheet by remember { mutableStateOf(false) }
    var showWorkspacesSheet by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        viewModel.refreshAccount()
    }

    ModalNavigationDrawer(
        drawerState = drawerState,
        drawerContent = {
            ModalDrawerSheet(
                drawerContainerColor = MaterialTheme.colorScheme.surfaceContainerLow,
                modifier = Modifier.width(304.dp)
            ) {
                // Drawer Brand Header
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 20.dp, vertical = 20.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Surface(
                        shape = RoundedCornerShape(12.dp),
                        color = Color(0xFF0B0F17),
                        border = BorderStroke(1.dp, TorveIndigo.copy(alpha = 0.45f)),
                        modifier = Modifier.size(40.dp)
                    ) {
                        Image(
                            painter = painterResource(id = R.drawable.ic_torve_logo),
                            contentDescription = stringResource(R.string.cd_torve_logo),
                            modifier = Modifier
                                .fillMaxSize()
                                .padding(5.dp)
                        )
                    }
                    Column {
                        Text(
                            text = "Torve AI",
                            style = MaterialTheme.typography.titleLarge,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                        Text(
                            text = "Harness v1.0.0 • ${sessionState.me?.user?.plan?.uppercase() ?: sessionState.credentials?.userPlan?.uppercase() ?: "ACTIVE"}",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.secondary
                        )
                    }
                }

                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                Spacer(Modifier.height(10.dp))

                NavigationDrawerItem(
                    label = { Text(stringResource(R.string.nav_all_threads)) },
                    icon = { Icon(Icons.Filled.Inbox, contentDescription = null) },
                    selected = uiState.selectedFilter == InboxFilter.ALL,
                    onClick = {
                        viewModel.setFilter(InboxFilter.ALL)
                        scope.launch { drawerState.close() }
                    },
                    modifier = Modifier
                        .padding(NavigationDrawerItemDefaults.ItemPadding)
                        .testTag("drawer_all_threads")
                )

                NavigationDrawerItem(
                    label = { Text(stringResource(R.string.nav_workspaces)) },
                    icon = { Icon(Icons.Filled.Folder, contentDescription = null) },
                    badge = {
                        if (uiState.workspaces.isNotEmpty()) {
                            Text(
                                text = "${uiState.workspaces.size}",
                                style = MaterialTheme.typography.labelSmall
                            )
                        }
                    },
                    selected = false,
                    onClick = {
                        scope.launch { drawerState.close() }
                        showWorkspacesSheet = true
                    },
                    modifier = Modifier
                        .padding(NavigationDrawerItemDefaults.ItemPadding)
                        .testTag("drawer_workspaces")
                )

                NavigationDrawerItem(
                    label = { Text(stringResource(R.string.nav_running_servers)) },
                    icon = { Icon(Icons.Outlined.Dns, contentDescription = null) },
                    badge = {
                        if (uiState.runningServers.isNotEmpty()) {
                            Badge(containerColor = TorveEmerald, contentColor = Color(0xFF032318)) {
                                Text("${uiState.runningServers.size}")
                            }
                        }
                    },
                    selected = false,
                    onClick = {
                        scope.launch { drawerState.close() }
                        showServersSheet = true
                    },
                    modifier = Modifier
                        .padding(NavigationDrawerItemDefaults.ItemPadding)
                        .testTag("drawer_running_servers")
                )

                Spacer(Modifier.height(6.dp))
                HorizontalDivider(
                    modifier = Modifier.padding(horizontal = 20.dp),
                    color = MaterialTheme.colorScheme.outlineVariant
                )
                Spacer(Modifier.height(6.dp))

                NavigationDrawerItem(
                    label = { Text(stringResource(R.string.nav_usage_plan)) },
                    icon = { Icon(Icons.Filled.QueryStats, contentDescription = null) },
                    selected = false,
                    onClick = {
                        scope.launch { drawerState.close() }
                        onOpenUsage()
                    },
                    modifier = Modifier
                        .padding(NavigationDrawerItemDefaults.ItemPadding)
                        .testTag("drawer_usage_plan")
                )

                NavigationDrawerItem(
                    label = { Text(stringResource(R.string.nav_settings)) },
                    icon = { Icon(Icons.Filled.Settings, contentDescription = null) },
                    selected = false,
                    onClick = {
                        scope.launch { drawerState.close() }
                        onOpenSettings()
                    },
                    modifier = Modifier
                        .padding(NavigationDrawerItemDefaults.ItemPadding)
                        .testTag("drawer_settings")
                )
            }
        }
    ) {
        Scaffold(
            contentWindowInsets = WindowInsets.safeDrawing,
            containerColor = MaterialTheme.colorScheme.background,
            snackbarHost = { SnackbarHost(snackbarHostState) },
            floatingActionButton = {
                ExtendedFloatingActionButton(
                    onClick = { viewModel.createNewTask(onOpenThread) },
                    icon = { Icon(Icons.Filled.Edit, contentDescription = null) },
                    text = { Text(stringResource(R.string.inbox_new_task)) },
                    containerColor = MaterialTheme.colorScheme.primaryContainer,
                    contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
                    shape = RoundedCornerShape(18.dp),
                    modifier = Modifier.testTag("new_task_fab")
                )
            }
        ) { innerPadding ->
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding)
            ) {
                // Gmail-Style Floating Search Pill
                GmailSearchPill(
                    query = uiState.searchQuery,
                    onQueryChange = viewModel::setSearchQuery,
                    userInitial = deriveUserInitial(sessionState),
                    hasActiveServer = uiState.runningServers.isNotEmpty(),
                    onOpenDrawer = { scope.launch { drawerState.open() } },
                    onOpenAccount = {
                        viewModel.refreshAccount()
                        showAccountSheet = true
                    }
                )

                // Horizontal Filter Chips
                LazyRow(
                    contentPadding = PaddingValues(horizontal = 16.dp, vertical = 6.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    items(InboxFilter.entries) { filter ->
                        val selected = uiState.selectedFilter == filter
                        FilterChip(
                            selected = selected,
                            onClick = { viewModel.setFilter(filter) },
                            label = {
                                Text(
                                    text = filter.label,
                                    style = MaterialTheme.typography.labelLarge
                                )
                            },
                            colors = FilterChipDefaults.filterChipColors(
                                selectedContainerColor = MaterialTheme.colorScheme.primaryContainer,
                                selectedLabelColor = MaterialTheme.colorScheme.onPrimaryContainer
                            ),
                            modifier = Modifier.testTag("filter_chip_${filter.name.lowercase()}")
                        )
                    }
                }

                // Section Header Label
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 20.dp, vertical = 6.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = uiState.selectedFilter.label.uppercase(),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    if (uiState.runningServers.isNotEmpty()) {
                        Surface(
                            shape = RoundedCornerShape(999.dp),
                            color = TorveEmerald.copy(alpha = 0.14f),
                            modifier = Modifier.clickable { showServersSheet = true }
                        ) {
                            Text(
                                text = "● ${uiState.runningServers.size} live server${if (uiState.runningServers.size > 1) "s" else ""}",
                                style = MaterialTheme.typography.labelSmall,
                                color = TorveEmerald,
                                modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp)
                            )
                        }
                    }
                }

                if (uiState.items.isEmpty()) {
                    InboxEmptyState(
                        isSearching = uiState.searchQuery.isNotBlank(),
                        onNewTask = { viewModel.createNewTask(onOpenThread) }
                    )
                } else {
                    LazyColumn(
                        modifier = Modifier
                            .fillMaxSize()
                            .testTag("inbox_thread_list"),
                        contentPadding = PaddingValues(bottom = 96.dp)
                    ) {
                        items(
                            items = uiState.items,
                            key = { it.thread.id }
                        ) { item ->
                            val dismissState = rememberSwipeToDismissBoxState(
                                confirmValueChange = { value ->
                                    when (value) {
                                        SwipeToDismissBoxValue.StartToEnd -> {
                                            viewModel.archiveThread(item.thread.id)
                                            scope.launch {
                                                val res = snackbarHostState.showSnackbar(
                                                    message = "Archived \"${item.thread.title}\"",
                                                    actionLabel = "Undo",
                                                    duration = SnackbarDuration.Short
                                                )
                                                if (res == SnackbarResult.ActionPerformed) {
                                                    viewModel.restoreThread(item.thread.id)
                                                }
                                            }
                                            true
                                        }
                                        SwipeToDismissBoxValue.EndToStart -> {
                                            viewModel.deleteThreadAndWorkspace(item)
                                            true
                                        }
                                        SwipeToDismissBoxValue.Settled -> false
                                    }
                                }
                            )

                            SwipeToDismissBox(
                                state = dismissState,
                                backgroundContent = {
                                    val isArchive = dismissState.targetValue == SwipeToDismissBoxValue.StartToEnd
                                    val bg by animateColorAsState(
                                        targetValue = when (dismissState.targetValue) {
                                            SwipeToDismissBoxValue.StartToEnd -> TorveEmerald.copy(alpha = 0.2f)
                                            SwipeToDismissBoxValue.EndToStart -> MaterialTheme.colorScheme.errorContainer
                                            SwipeToDismissBoxValue.Settled -> Color.Transparent
                                        },
                                        label = "swipe_bg"
                                    )
                                    Box(
                                        modifier = Modifier
                                            .fillMaxSize()
                                            .background(bg)
                                            .padding(horizontal = 24.dp),
                                        contentAlignment = if (isArchive) Alignment.CenterStart else Alignment.CenterEnd
                                    ) {
                                        Icon(
                                            imageVector = if (isArchive) Icons.Outlined.Archive else Icons.Filled.Delete,
                                            contentDescription = if (isArchive) "Archive" else "Delete",
                                            tint = if (isArchive) TorveEmerald else MaterialTheme.colorScheme.error
                                        )
                                    }
                                }
                            ) {
                                ThreadListRow(
                                    item = item,
                                    onClick = { onOpenThread(item.thread.id) },
                                    onToggleStar = { viewModel.toggleStar(item.thread.id, item.thread.isStarred) },
                                    onOpenPreview = { port -> onOpenLivePreview(item.thread.workspaceId, port) }
                                )
                            }
                        }
                    }
                }
            }
        }
    }

    if (showAccountSheet) {
        ModalBottomSheet(
            onDismissRequest = { showAccountSheet = false },
            containerColor = MaterialTheme.colorScheme.surfaceContainerLow
        ) {
            AccountQuotaBottomSheet(
                sessionState = sessionState,
                onOpenUsage = {
                    showAccountSheet = false
                    onOpenUsage()
                },
                onOpenSettings = {
                    showAccountSheet = false
                    onOpenSettings()
                },
                onSignOut = {
                    showAccountSheet = false
                    onSignOut()
                }
            )
        }
    }

    if (showServersSheet) {
        ModalBottomSheet(
            onDismissRequest = { showServersSheet = false },
            containerColor = MaterialTheme.colorScheme.surfaceContainerLow
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Text("Running Local Servers", style = MaterialTheme.typography.titleLarge)
                if (uiState.runningServers.isEmpty()) {
                    Text(
                        text = "No dev servers are currently running. Ask the agent to build a web project and start a local server.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(bottom = 24.dp)
                    )
                } else {
                    uiState.runningServers.forEach { srv ->
                        Surface(
                            shape = RoundedCornerShape(16.dp),
                            color = MaterialTheme.colorScheme.surfaceContainer,
                            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(14.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(srv.workspaceName, style = MaterialTheme.typography.titleMedium)
                                    Text(
                                        text = "${srv.previewUrl} • ${srv.mode}",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = TorveEmerald
                                    )
                                }
                                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                    TextButton(onClick = {
                                        showServersSheet = false
                                        onOpenLivePreview(srv.workspaceId, srv.port)
                                    }) {
                                        Text("Preview")
                                    }
                                    TextButton(onClick = { viewModel.stopServer(srv.workspaceId) }) {
                                        Text("Stop", color = MaterialTheme.colorScheme.error)
                                    }
                                }
                            }
                        }
                    }
                    Spacer(Modifier.height(20.dp))
                }
            }
        }
    }

    if (showWorkspacesSheet) {
        ModalBottomSheet(
            onDismissRequest = { showWorkspacesSheet = false },
            containerColor = MaterialTheme.colorScheme.surfaceContainerLow
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Text("Sandbox Workspaces", style = MaterialTheme.typography.titleLarge)
                if (uiState.workspaces.isEmpty()) {
                    Text(
                        text = "No workspaces created yet. Tap New Task to spin up an isolated sandbox directory.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(bottom = 24.dp)
                    )
                } else {
                    uiState.workspaces.take(12).forEach { ws ->
                        Surface(
                            shape = RoundedCornerShape(14.dp),
                            color = MaterialTheme.colorScheme.surfaceContainer,
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable {
                                    showWorkspacesSheet = false
                                    onOpenWorkspaceExplorer(ws.id)
                                }
                        ) {
                            Row(
                                modifier = Modifier.padding(14.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(12.dp)
                            ) {
                                Icon(
                                    imageVector = Icons.Filled.Folder,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.secondary
                                )
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(ws.name, style = MaterialTheme.typography.titleMedium)
                                    Text(
                                        text = ws.id,
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                                Text(
                                    text = ws.projectType.uppercase(),
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.primary
                                )
                            }
                        }
                    }
                    Spacer(Modifier.height(20.dp))
                }
            }
        }
    }
}

@Composable
private fun GmailSearchPill(
    query: String,
    onQueryChange: (String) -> Unit,
    userInitial: String,
    hasActiveServer: Boolean,
    onOpenDrawer: () -> Unit,
    onOpenAccount: () -> Unit
) {
    Surface(
        shape = RoundedCornerShape(999.dp),
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        tonalElevation = 2.dp,
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 10.dp)
            .height(52.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(
                onClick = onOpenDrawer,
                modifier = Modifier.testTag("inbox_menu_button")
            ) {
                Icon(
                    imageVector = Icons.Filled.Menu,
                    contentDescription = stringResource(R.string.cd_open_drawer),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            Box(
                modifier = Modifier.weight(1f),
                contentAlignment = Alignment.CenterStart
            ) {
                if (query.isEmpty()) {
                    Text(
                        text = stringResource(R.string.inbox_search_placeholder),
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                BasicTextField(
                    value = query,
                    onValueChange = onQueryChange,
                    singleLine = true,
                    textStyle = MaterialTheme.typography.bodyLarge.copy(
                        color = MaterialTheme.colorScheme.onSurface
                    ),
                    cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("inbox_search_input")
                )
            }

            if (query.isNotEmpty()) {
                IconButton(onClick = { onQueryChange("") }) {
                    Icon(
                        imageVector = Icons.Filled.Close,
                        contentDescription = "Clear search",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            Box(
                modifier = Modifier
                    .padding(end = 6.dp)
                    .size(36.dp)
                    .clip(CircleShape)
                    .background(TorveIndigo)
                    .clickable(onClick = onOpenAccount)
                    .testTag("inbox_account_badge"),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = userInitial,
                    style = MaterialTheme.typography.labelLarge,
                    color = Color.White,
                    fontWeight = FontWeight.Bold
                )
                if (hasActiveServer) {
                    Box(
                        modifier = Modifier
                            .align(Alignment.BottomEnd)
                            .size(10.dp)
                            .clip(CircleShape)
                            .background(TorveEmerald)
                    )
                }
            }
        }
    }
}

@Composable
private fun ThreadListRow(
    item: InboxThreadItem,
    onClick: () -> Unit,
    onToggleStar: () -> Unit,
    onOpenPreview: (Int) -> Unit
) {
    val avatarLetter = (item.thread.title.firstOrNull()?.uppercaseChar() ?: 'T').toString()
    val avatarColor = when (Math.abs(item.thread.id.hashCode()) % 4) {
        0 -> TorveIndigo
        1 -> Color(0xFF0E7490)
        2 -> Color(0xFF059669)
        else -> Color(0xFF7C3AED)
    }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.background)
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 12.dp)
            .testTag("thread_row_${item.thread.id}"),
        verticalAlignment = Alignment.Top,
        horizontalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        // Workspace / Model Avatar
        Box(
            modifier = Modifier
                .size(44.dp)
                .clip(CircleShape)
                .background(avatarColor.copy(alpha = 0.22f)),
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = avatarLetter,
                style = MaterialTheme.typography.titleMedium,
                color = avatarColor,
                fontWeight = FontWeight.Bold
            )
        }

        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(3.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = item.thread.title,
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f)
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    text = formatShortTimestamp(item.thread.updatedAt),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(
                    text = item.thread.lastSnippet.ifBlank { "Tap to start coding in sandbox..." },
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f)
                )
                IconButton(
                    onClick = onToggleStar,
                    modifier = Modifier.size(32.dp)
                ) {
                    Icon(
                        imageVector = if (item.thread.isStarred) Icons.Filled.Star else Icons.Outlined.StarBorder,
                        contentDescription = if (item.thread.isStarred) "Unstar thread" else "Star thread",
                        tint = if (item.thread.isStarred) TorveAmber else MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(20.dp)
                    )
                }
            }

            // Metadata chips row (Model + Live Server Badge)
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                if (item.thread.modelId.isNotBlank()) {
                    Text(
                        text = item.thread.modelId,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                item.runningServer?.let { server ->
                    Surface(
                        shape = RoundedCornerShape(999.dp),
                        color = TorveEmerald.copy(alpha = 0.16f),
                        border = BorderStroke(1.dp, TorveEmerald.copy(alpha = 0.4f)),
                        modifier = Modifier.clickable { onOpenPreview(server.port) }
                    ) {
                        Text(
                            text = "● :${server.port}",
                            style = MaterialTheme.typography.labelSmall,
                            fontFamily = JetBrainsMonoFamily,
                            color = TorveEmerald,
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp)
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun InboxEmptyState(
    isSearching: Boolean,
    onNewTask: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Surface(
            shape = RoundedCornerShape(20.dp),
            color = MaterialTheme.colorScheme.surfaceContainer,
            modifier = Modifier.size(72.dp)
        ) {
            Box(contentAlignment = Alignment.Center) {
                Icon(
                    imageVector = Icons.Filled.Terminal,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.secondary,
                    modifier = Modifier.size(34.dp)
                )
            }
        }
        Spacer(Modifier.height(16.dp))
        Text(
            text = if (isSearching) "No matching threads" else "Your Agentic Inbox is Ready",
            style = MaterialTheme.typography.titleLarge,
            color = MaterialTheme.colorScheme.onSurface
        )
        Spacer(Modifier.height(8.dp))
        Text(
            text = if (isSearching) {
                "Try a different search keyword or switch filters."
            } else {
                "Start a new task to launch an isolated Linux workspace, stream Torve AI reasoning, and preview web apps live."
            },
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        if (!isSearching) {
            Spacer(Modifier.height(20.dp))
            OutlinedButton(onClick = onNewTask) {
                Icon(Icons.Filled.Edit, contentDescription = null, modifier = Modifier.size(16.dp))
                Spacer(Modifier.width(8.dp))
                Text("Start First Task")
            }
        }
    }
}

@Composable
private fun AccountQuotaBottomSheet(
    sessionState: AccountSessionState,
    onOpenUsage: () -> Unit,
    onOpenSettings: () -> Unit,
    onSignOut: () -> Unit
) {
    val user = sessionState.me?.user
    val quota = sessionState.me?.quota
    val numFmt = remember { NumberFormat.getIntegerInstance() }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 20.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Box(
                modifier = Modifier
                    .size(48.dp)
                    .clip(CircleShape)
                    .background(TorveIndigo),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = deriveUserInitial(sessionState),
                    style = MaterialTheme.typography.titleLarge,
                    color = Color.White
                )
            }
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = user?.name?.ifBlank { null } ?: sessionState.credentials?.userName ?: "Torve Developer",
                    style = MaterialTheme.typography.titleMedium
                )
                Text(
                    text = user?.email?.ifBlank { null } ?: sessionState.credentials?.userEmail ?: sessionState.credentials?.keyPrefix.orEmpty(),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Surface(
                shape = RoundedCornerShape(999.dp),
                color = MaterialTheme.colorScheme.primaryContainer
            ) {
                Text(
                    text = (user?.plan ?: sessionState.credentials?.userPlan ?: "active").uppercase(),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onPrimaryContainer,
                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp)
                )
            }
        }

        // Daily Quota Card
        Surface(
            shape = RoundedCornerShape(16.dp),
            color = MaterialTheme.colorScheme.surfaceContainer,
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text("Today's Usage", style = MaterialTheme.typography.titleSmall)
                    val isLimited = quota?.rateLimitStatus?.lowercase() == "limited"
                    Text(
                        text = if (isLimited) "RATE LIMITED" else "STATUS: OK",
                        style = MaterialTheme.typography.labelSmall,
                        color = if (isLimited) TorveAmber else TorveEmerald
                    )
                }
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(
                        text = "${numFmt.format(quota?.usedTokensToday ?: 0L)} tokens",
                        style = MaterialTheme.typography.bodyMedium,
                        fontFamily = JetBrainsMonoFamily
                    )
                    Text(
                        text = "${quota?.usedRequestsToday ?: 0} requests",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                LinearProgressIndicator(
                    progress = {
                        ((quota?.usedRequestsToday ?: 0) / 100f).coerceIn(0.05f, 1f)
                    },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(6.dp)
                        .clip(RoundedCornerShape(999.dp)),
                    color = TorveCyan
                )
                val resetsAt = quota?.resetsAt
                if (!resetsAt.isNullOrBlank()) {
                    Text(
                        text = "Resets UTC: ${resetsAt.take(16).replace('T', ' ')}",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = 20.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            OutlinedButton(
                onClick = onOpenUsage,
                modifier = Modifier.weight(1f)
            ) {
                Text("Full Analytics")
            }
            OutlinedButton(
                onClick = onOpenSettings,
                modifier = Modifier.weight(1f)
            ) {
                Text("Settings")
            }
            TextButton(onClick = onSignOut) {
                Text("Sign Out", color = MaterialTheme.colorScheme.error)
            }
        }
    }
}

private fun deriveUserInitial(sessionState: AccountSessionState): String {
    val name = sessionState.me?.user?.name?.takeIf { it.isNotBlank() }
        ?: sessionState.credentials?.userName?.takeIf { it.isNotBlank() }
        ?: sessionState.me?.user?.email?.takeIf { it.isNotBlank() }
        ?: "T"
    return name.first().uppercaseChar().toString()
}

private fun formatShortTimestamp(epochMs: Long): String {
    val diff = System.currentTimeMillis() - epochMs
    return when {
        diff < 60_000L -> "now"
        diff < 3_600_000L -> "${diff / 60_000L}m"
        diff < 86_400_000L -> SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(epochMs))
        else -> SimpleDateFormat("MMM d", Locale.getDefault()).format(Date(epochMs))
    }
}
