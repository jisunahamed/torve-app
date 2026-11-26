package com.example.feature.preview

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Save
import androidx.compose.material.icons.filled.Terminal
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.R
import com.example.core.TorveContainer
import com.example.ui.theme.JetBrainsMonoFamily
import com.example.ui.theme.TorveCodeBgDark
import com.example.ui.theme.TorveCyan
import com.example.ui.theme.TorveEmerald
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Workspace File Explorer & Interactive Raw Sandbox Terminal.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun WorkspaceExplorerScreen(
    workspaceId: String,
    container: TorveContainer,
    onNavigateBack: () -> Unit
) {
    val scope = rememberCoroutineScope()
    val snackbarHostState = remember { SnackbarHostState() }
    val workspaceDir = remember(workspaceId) { container.sandboxBootstrap.ensureWorkspaceDir(workspaceId) }

    var selectedTab by remember { mutableIntStateOf(0) }
    var currentDir by remember { mutableStateOf(workspaceDir) }
    var fileList by remember { mutableStateOf<List<File>>(emptyList()) }
    var editingFile by remember { mutableStateOf<File?>(null) }
    var editorText by remember { mutableStateOf("") }

    var terminalCommand by remember { mutableStateOf("") }
    var terminalHistory by remember { mutableStateOf("Torve Sandbox Shell — jailed to ${workspaceDir.name}\n") }
    var runningCmd by remember { mutableStateOf(false) }

    fun reloadFiles() {
        val entries = currentDir.listFiles()
            ?.sortedWith(compareBy<File> { !it.isDirectory }.thenBy { it.name })
            .orEmpty()
        fileList = entries
    }

    LaunchedEffect(currentDir) {
        reloadFiles()
    }

    Scaffold(
        contentWindowInsets = WindowInsets.safeDrawing,
        containerColor = MaterialTheme.colorScheme.background,
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
            TopAppBar(
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surfaceContainerLow
                ),
                navigationIcon = {
                    IconButton(
                        onClick = {
                            if (editingFile != null) {
                                editingFile = null
                            } else {
                                onNavigateBack()
                            }
                        },
                        modifier = Modifier.testTag("explorer_back_button")
                    ) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(R.string.cd_back)
                        )
                    }
                },
                title = {
                    Column {
                        Text(
                            text = editingFile?.name ?: "Workspace Explorer",
                            style = MaterialTheme.typography.titleMedium
                        )
                        Text(
                            text = workspaceId,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                },
                actions = {
                    editingFile?.let { file ->
                        IconButton(
                            onClick = {
                                scope.launch {
                                    withContext(Dispatchers.IO) { file.writeText(editorText) }
                                    snackbarHostState.showSnackbar("Saved ${file.name}")
                                }
                            },
                            modifier = Modifier.testTag("save_file_button")
                        ) {
                            Icon(
                                imageVector = Icons.Filled.Save,
                                contentDescription = "Save file",
                                tint = TorveEmerald
                            )
                        }
                    }
                }
            )
        }
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .imePadding()
        ) {
            if (editingFile == null) {
                TabRow(selectedTabIndex = selectedTab) {
                    Tab(
                        selected = selectedTab == 0,
                        onClick = { selectedTab = 0 },
                        text = { Text("Files") },
                        icon = { Icon(Icons.Filled.Folder, contentDescription = null, modifier = Modifier.size(18.dp)) }
                    )
                    Tab(
                        selected = selectedTab == 1,
                        onClick = { selectedTab = 1 },
                        text = { Text("Terminal") },
                        icon = { Icon(Icons.Filled.Terminal, contentDescription = null, modifier = Modifier.size(18.dp)) }
                    )
                }
            }

            if (editingFile != null) {
                // Lightweight monospace file editor
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(TorveCodeBgDark)
                        .padding(14.dp)
                ) {
                    BasicTextField(
                        value = editorText,
                        onValueChange = { editorText = it },
                        textStyle = MaterialTheme.typography.bodySmall.copy(
                            fontFamily = JetBrainsMonoFamily,
                            fontSize = 13.sp,
                            lineHeight = 19.sp,
                            color = Color(0xFFE2E8F0)
                        ),
                        cursorBrush = SolidColor(TorveCyan),
                        modifier = Modifier
                            .fillMaxSize()
                            .verticalScroll(rememberScrollState())
                            .testTag("workspace_file_editor")
                    )
                }
            } else if (selectedTab == 0) {
                // Directory Browser
                Column(modifier = Modifier.fillMaxSize()) {
                    if (currentDir.canonicalPath != workspaceDir.canonicalPath) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { currentDir = currentDir.parentFile ?: workspaceDir }
                                .padding(horizontal = 16.dp, vertical = 12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            Icon(Icons.Filled.Folder, contentDescription = null, tint = TorveCyan)
                            Text("..", style = MaterialTheme.typography.titleMedium, fontFamily = JetBrainsMonoFamily)
                        }
                        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                    }

                    if (fileList.isEmpty()) {
                        Column(
                            modifier = Modifier
                                .fillMaxSize()
                                .padding(28.dp),
                            verticalArrangement = Arrangement.Center,
                            horizontalAlignment = Alignment.CenterHorizontally
                        ) {
                            Text(
                                text = "This workspace directory is currently empty.",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    } else {
                        LazyColumn(modifier = Modifier.fillMaxSize()) {
                            items(fileList, key = { it.absolutePath }) { file ->
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clickable {
                                            if (file.isDirectory) {
                                                currentDir = file
                                            } else {
                                                scope.launch {
                                                    val content = withContext(Dispatchers.IO) {
                                                        runCatching { file.readText() }.getOrDefault("[Binary file]")
                                                    }
                                                    editorText = content
                                                    editingFile = file
                                                }
                                            }
                                        }
                                        .padding(horizontal = 16.dp, vertical = 13.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                                ) {
                                    Icon(
                                        imageVector = if (file.isDirectory) Icons.Filled.Folder else Icons.Filled.Description,
                                        contentDescription = null,
                                        tint = if (file.isDirectory) TorveCyan else MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                    Column(modifier = Modifier.weight(1f)) {
                                        Text(
                                            text = file.name,
                                            style = MaterialTheme.typography.bodyLarge,
                                            fontFamily = JetBrainsMonoFamily
                                        )
                                        Text(
                                            text = if (file.isDirectory) "Directory" else "${file.length()} bytes",
                                            style = MaterialTheme.typography.labelSmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                    }
                                }
                                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
                            }
                        }
                    }
                }
            } else {
                // Interactive Mini Sandbox Terminal
                Column(modifier = Modifier.fillMaxSize()) {
                    Text(
                        text = terminalHistory,
                        fontFamily = JetBrainsMonoFamily,
                        fontSize = 12.sp,
                        lineHeight = 18.sp,
                        color = Color(0xFFE2E8F0),
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxWidth()
                            .background(TorveCodeBgDark)
                            .verticalScroll(rememberScrollState())
                            .padding(14.dp)
                            .testTag("sandbox_terminal_output")
                    )

                    Surface(
                        color = MaterialTheme.colorScheme.surfaceContainerLow,
                        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(10.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            OutlinedTextField(
                                value = terminalCommand,
                                onValueChange = { terminalCommand = it },
                                placeholder = { Text("ls -la", fontFamily = JetBrainsMonoFamily) },
                                singleLine = true,
                                modifier = Modifier
                                    .weight(1f)
                                    .testTag("sandbox_terminal_input")
                            )
                            FilledTonalIconButton(
                                enabled = !runningCmd && terminalCommand.isNotBlank(),
                                onClick = {
                                    val cmd = terminalCommand.trim()
                                    terminalCommand = ""
                                    runningCmd = true
                                    terminalHistory += "\n$ $cmd\n"
                                    scope.launch {
                                        val res = container.sandboxBootstrap.executeShellCommand(
                                            workspaceDir = workspaceDir,
                                            command = cmd,
                                            timeoutSec = 20
                                        )
                                        terminalHistory += "${res.output}\n"
                                        runningCmd = false
                                        reloadFiles()
                                    }
                                },
                                modifier = Modifier
                                    .size(48.dp)
                                    .testTag("sandbox_terminal_run_button")
                            ) {
                                Icon(Icons.Filled.PlayArrow, contentDescription = "Run shell command")
                            }
                        }
                    }
                }
            }
        }
    }
}
