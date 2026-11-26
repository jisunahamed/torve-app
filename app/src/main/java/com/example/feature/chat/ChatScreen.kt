package com.example.feature.chat

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.BorderStroke
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
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.AttachFile
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.ChatBubbleOutline
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.Language
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.outlined.HourglassTop
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.R
import com.example.core.database.MessageEntity
import com.example.core.database.ToolCallLogEntity
import com.example.feature.skills.AgentSkill
import com.example.ui.theme.JetBrainsMonoFamily
import com.example.ui.theme.TorveAmber
import com.example.ui.theme.TorveCyan
import com.example.ui.theme.TorveEmerald
import com.example.ui.theme.TorveIndigo

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChatScreen(
    viewModel: ChatViewModel,
    onNavigateBack: () -> Unit,
    onOpenLivePreview: (String, Int) -> Unit,
    onOpenWorkspaceExplorer: (String) -> Unit
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val listState = rememberLazyListState()
    val snackbarHostState = remember { SnackbarHostState() }
    var promptInput by remember { mutableStateOf("") }
    var showModelSheet by remember { mutableStateOf(false) }

    val slashCandidates = remember(promptInput, uiState.enabledSkills) {
        val trimmed = promptInput.trimStart()
        if (!trimmed.startsWith("/") || trimmed.contains(" ") || trimmed.contains("\n")) {
            emptyList()
        } else {
            val token = trimmed.lowercase()
            uiState.enabledSkills.filter { skill ->
                skill.primarySlashCommand.lowercase().startsWith(token) ||
                    "/${skill.name}".lowercase().startsWith(token) ||
                    skill.triggers.any { it.lowercase().startsWith(token) } ||
                    token == "/"
            }
        }
    }

    val composerMatchedSkill = remember(promptInput, uiState.enabledSkills) {
        val trimmed = promptInput.trimStart()
        if (!trimmed.startsWith("/")) return@remember null
        val firstToken = trimmed.substringBefore(" ").substringBefore("\n").trim()
        if (firstToken.length < 2) return@remember null
        uiState.enabledSkills.firstOrNull { skill ->
            skill.triggers.any { it.equals(firstToken, ignoreCase = true) } ||
                "/${skill.name}".equals(firstToken, ignoreCase = true)
        }
    }

    val attachLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null) {
            viewModel.importFileToWorkspace(context, uri)
        }
    }

    LaunchedEffect(uiState.attachedNotice) {
        uiState.attachedNotice?.let { msg ->
            snackbarHostState.showSnackbar(msg)
            viewModel.clearAttachedNotice()
        }
    }

    LaunchedEffect(uiState.messages.size, uiState.toolLogsByMessageId.size) {
        if (uiState.messages.isNotEmpty()) {
            listState.animateScrollToItem(uiState.messages.size - 1)
        }
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
                        onClick = onNavigateBack,
                        modifier = Modifier.testTag("chat_back_button")
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
                            text = uiState.thread?.title ?: "Agent Task",
                            style = MaterialTheme.typography.titleMedium,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        Text(
                            text = uiState.workspace?.name ?: "Isolated Sandbox",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                },
                actions = {
                    val runningServer = uiState.runningServer
                    if (runningServer != null) {
                        Surface(
                            shape = RoundedCornerShape(999.dp),
                            color = TorveEmerald.copy(alpha = 0.18f),
                            border = BorderStroke(1.dp, TorveEmerald.copy(alpha = 0.5f)),
                            modifier = Modifier
                                .padding(end = 4.dp)
                                .clickable {
                                    onOpenLivePreview(runningServer.workspaceId, runningServer.port)
                                }
                                .testTag("chat_live_preview_pill")
                        ) {
                            Row(
                                modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(5.dp)
                            ) {
                                Icon(
                                    imageVector = Icons.Filled.Language,
                                    contentDescription = stringResource(R.string.cd_live_preview),
                                    tint = TorveEmerald,
                                    modifier = Modifier.size(16.dp)
                                )
                                Text(
                                    text = ":${runningServer.port}",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = TorveEmerald,
                                    fontWeight = FontWeight.Bold
                                )
                            }
                        }
                    } else {
                        IconButton(
                            onClick = {
                                val wsId = uiState.thread?.workspaceId ?: return@IconButton
                                onOpenLivePreview(wsId, 8080)
                            },
                            modifier = Modifier.testTag("chat_live_preview_icon")
                        ) {
                            Icon(
                                imageVector = Icons.Filled.Language,
                                contentDescription = stringResource(R.string.cd_live_preview),
                                tint = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }

                    IconButton(
                        onClick = {
                            val wsId = uiState.thread?.workspaceId ?: return@IconButton
                            onOpenWorkspaceExplorer(wsId)
                        },
                        modifier = Modifier.testTag("chat_explorer_button")
                    ) {
                        Icon(
                            imageVector = Icons.Filled.FolderOpen,
                            contentDescription = stringResource(R.string.cd_workspace_explorer),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant
                        )
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
            // Subtle Rate-Limit Status Pill
            AnimatedVisibility(visible = uiState.rateLimitBanner != null) {
                uiState.rateLimitBanner?.let { banner ->
                    Surface(
                        color = TorveAmber.copy(alpha = 0.16f),
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("rate_limit_banner")
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Outlined.HourglassTop,
                                contentDescription = null,
                                tint = TorveAmber,
                                modifier = Modifier.size(16.dp)
                            )
                            Text(
                                text = banner.message,
                                style = MaterialTheme.typography.labelMedium,
                                color = TorveAmber
                            )
                        }
                    }
                }
            }

            // Error Banner
            AnimatedVisibility(visible = !uiState.runState.errorMessage.isNullOrBlank()) {
                Surface(
                    color = MaterialTheme.colorScheme.errorContainer,
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { viewModel.clearError() }
                ) {
                    Text(
                        text = "${uiState.runState.errorMessage}  (tap to dismiss)",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onErrorContainer,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp)
                    )
                }
            }

            // Message Stream
            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth(),
                contentAlignment = Alignment.TopCenter
            ) {
                if (uiState.messages.isEmpty()) {
                    ChatStarterPrompts(
                        onPickPrompt = { suggestion ->
                            promptInput = suggestion
                        }
                    )
                } else {
                    LazyColumn(
                        state = listState,
                        modifier = Modifier
                            .fillMaxSize()
                            .widthIn(max = 760.dp)
                            .testTag("chat_message_list"),
                        contentPadding = PaddingValues(horizontal = 14.dp, vertical = 12.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        items(uiState.messages, key = { it.id }) { message ->
                            val logs = uiState.toolLogsByMessageId[message.id].orEmpty()
                            val inferredSkillName = remember(message.id, logs, uiState.runState.activeSkillName) {
                                logs.firstOrNull { it.toolName == "load_skill" }
                                    ?.summary
                                    ?.substringAfter("Loaded skill ", "")
                                    ?.substringBefore(" ")
                                    ?.takeIf { it.isNotBlank() }
                                    ?: if (message.isStreaming) uiState.runState.activeSkillName else null
                            }
                            ChatMessageBubble(
                                message = message,
                                toolLogs = logs,
                                activeSkillName = inferredSkillName
                            )
                        }

                        // Pending Shell Approval Card
                        uiState.runState.pendingApproval?.let { approval ->
                            item(key = "approval_${approval.toolLogId}") {
                                ShellApprovalCard(
                                    approval = approval,
                                    onApprove = { viewModel.respondToShellApproval(approval.toolLogId, true) },
                                    onDeny = { viewModel.respondToShellApproval(approval.toolLogId, false) }
                                )
                            }
                        }

                        // Step Limit Reached Card
                        if (uiState.runState.stepLimitReached && !uiState.runState.isRunning) {
                            item(key = "step_limit_reached") {
                                Surface(
                                    shape = RoundedCornerShape(16.dp),
                                    color = MaterialTheme.colorScheme.surfaceContainerHigh,
                                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
                                    modifier = Modifier.fillMaxWidth()
                                ) {
                                    Row(
                                        modifier = Modifier.padding(14.dp),
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.SpaceBetween
                                    ) {
                                        Column(modifier = Modifier.weight(1f)) {
                                            Text(
                                                text = "Step limit (${uiState.runState.maxSteps}) reached",
                                                style = MaterialTheme.typography.titleSmall
                                            )
                                            Text(
                                                text = "Tap Continue to allow another ${uiState.runState.maxSteps} agentic steps.",
                                                style = MaterialTheme.typography.bodySmall,
                                                color = MaterialTheme.colorScheme.onSurfaceVariant
                                            )
                                        }
                                        Button(
                                            onClick = { viewModel.continueStepLimit() },
                                            modifier = Modifier.testTag("continue_step_limit_button")
                                        ) {
                                            Icon(Icons.Filled.PlayArrow, contentDescription = null, modifier = Modifier.size(16.dp))
                                            Spacer(Modifier.width(4.dp))
                                            Text("Continue")
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }

            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)

            // Slash-Command Autocomplete Popup above the Composer
            AnimatedVisibility(visible = slashCandidates.isNotEmpty()) {
                SlashCommandPopup(
                    skills = slashCandidates,
                    onSelectSkill = { skill ->
                        promptInput = "${skill.primarySlashCommand} "
                    }
                )
            }

            // Bottom Composer Bar with Compact Model Switcher Pill, Chat-Only Indicator & Active Skill Pill
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(MaterialTheme.colorScheme.surfaceContainerLow)
                    .navigationBarsPadding()
                    .padding(horizontal = 12.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                // Model Switcher Pill + Chat-Only Mode Indicator + Active Step Counter
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        modifier = Modifier.weight(1f, fill = false)
                    ) {
                        Surface(
                            shape = RoundedCornerShape(999.dp),
                            color = MaterialTheme.colorScheme.surfaceContainerHigh,
                            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
                            modifier = Modifier
                                .clickable { showModelSheet = true }
                                .testTag("model_switcher_pill")
                        ) {
                            Row(
                                modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(6.dp)
                            ) {
                                Text(
                                    text = uiState.selectedModel?.displayName
                                        ?: uiState.selectedModelId.ifBlank { "Select Model" },
                                    style = MaterialTheme.typography.labelLarge,
                                    color = MaterialTheme.colorScheme.onSurface,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                                if (uiState.supportsTools) {
                                    Icon(
                                        imageVector = Icons.Filled.Build,
                                        contentDescription = "Tools enabled",
                                        tint = TorveCyan,
                                        modifier = Modifier.size(13.dp)
                                    )
                                }
                                if (uiState.selectedModel?.supportsReasoning == true) {
                                    Icon(
                                        imageVector = Icons.Filled.AutoAwesome,
                                        contentDescription = "Reasoning enabled",
                                        tint = TorveAmber,
                                        modifier = Modifier.size(13.dp)
                                    )
                                }
                                Icon(
                                    imageVector = Icons.Filled.KeyboardArrowDown,
                                    contentDescription = "Switch model",
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.size(16.dp)
                                )
                            }
                        }

                        if (!uiState.supportsTools) {
                            Surface(
                                shape = RoundedCornerShape(999.dp),
                                color = TorveAmber.copy(alpha = 0.16f),
                                border = BorderStroke(1.dp, TorveAmber.copy(alpha = 0.45f)),
                                modifier = Modifier.testTag("chat_only_mode_pill")
                            ) {
                                Row(
                                    modifier = Modifier.padding(horizontal = 9.dp, vertical = 5.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                                ) {
                                    Icon(
                                        imageVector = Icons.Filled.ChatBubbleOutline,
                                        contentDescription = null,
                                        tint = TorveAmber,
                                        modifier = Modifier.size(12.dp)
                                    )
                                    Text(
                                        text = "Chat-Only Mode",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = TorveAmber,
                                        fontWeight = FontWeight.SemiBold
                                    )
                                }
                            }
                        }
                    }

                    if (uiState.runState.isRunning) {
                        Text(
                            text = "Step ${uiState.runState.currentStep}/${uiState.runState.maxSteps}",
                            style = MaterialTheme.typography.labelSmall,
                            color = TorveCyan
                        )
                    }
                }

                // Active Skill Pill (either typed via /command or currently running in harness)
                val displayedSkillName = composerMatchedSkill?.name ?: uiState.runState.activeSkillName
                AnimatedVisibility(visible = displayedSkillName != null) {
                    displayedSkillName?.let { skillName ->
                        ActiveSkillPill(
                            skillName = skillName,
                            onClear = if (composerMatchedSkill != null) {
                                {
                                    val firstToken = promptInput.trimStart().substringBefore(" ")
                                    promptInput = promptInput.trimStart().removePrefix(firstToken).trimStart()
                                }
                            } else null
                        )
                    }
                }

                // Multi-line Input + Attach + Send/Stop Row
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.Bottom,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    IconButton(
                        onClick = { attachLauncher.launch(arrayOf("*/*")) },
                        modifier = Modifier
                            .size(48.dp)
                            .testTag("chat_attach_button")
                    ) {
                        Icon(
                            imageVector = Icons.Filled.AttachFile,
                            contentDescription = stringResource(R.string.cd_attach_file),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }

                    Surface(
                        shape = RoundedCornerShape(22.dp),
                        color = MaterialTheme.colorScheme.surfaceContainer,
                        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
                        modifier = Modifier.weight(1f)
                    ) {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 16.dp, vertical = 12.dp)
                        ) {
                            if (promptInput.isEmpty()) {
                                Text(
                                    text = if (uiState.supportsTools) {
                                        "Type / for skills, or ask Torve AI to build..."
                                    } else {
                                        "Chat-Only Mode — ask Torve AI anything..."
                                    },
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                            BasicTextField(
                                value = promptInput,
                                onValueChange = { promptInput = it },
                                textStyle = MaterialTheme.typography.bodyMedium.copy(
                                    color = MaterialTheme.colorScheme.onSurface
                                ),
                                cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                                maxLines = 6,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .testTag("chat_composer_input")
                            )
                        }
                    }

                    if (uiState.runState.isRunning) {
                        FilledIconButton(
                            onClick = { viewModel.stopGeneration() },
                            colors = IconButtonDefaults.filledIconButtonColors(
                                containerColor = MaterialTheme.colorScheme.error,
                                contentColor = Color.White
                            ),
                            modifier = Modifier
                                .size(48.dp)
                                .testTag("chat_stop_button")
                        ) {
                            Icon(
                                imageVector = Icons.Filled.Stop,
                                contentDescription = stringResource(R.string.cd_stop_generation)
                            )
                        }
                    } else {
                        FilledIconButton(
                            onClick = {
                                val text = promptInput.trim()
                                if (text.isNotEmpty()) {
                                    promptInput = ""
                                    viewModel.sendMessage(text)
                                }
                            },
                            enabled = promptInput.isNotBlank(),
                            colors = IconButtonDefaults.filledIconButtonColors(
                                containerColor = TorveIndigo,
                                contentColor = Color.White
                            ),
                            modifier = Modifier
                                .size(48.dp)
                                .testTag("chat_send_button")
                        ) {
                            Icon(
                                imageVector = Icons.AutoMirrored.Filled.Send,
                                contentDescription = stringResource(R.string.cd_send_message)
                            )
                        }
                    }
                }
            }
        }
    }

    if (showModelSheet) {
        ModalBottomSheet(
            onDismissRequest = { showModelSheet = false },
            containerColor = MaterialTheme.colorScheme.surfaceContainerLow
        ) {
            ModelSwitcherSheetContent(
                models = uiState.models,
                selectedModelId = uiState.selectedModelId,
                onSelectModel = { model ->
                    viewModel.selectModel(model)
                    showModelSheet = false
                }
            )
        }
    }
}

@Composable
private fun SlashCommandPopup(
    skills: List<AgentSkill>,
    onSelectSkill: (AgentSkill) -> Unit
) {
    Surface(
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        border = BorderStroke(1.dp, TorveIndigo.copy(alpha = 0.4f)),
        shape = RoundedCornerShape(topStart = 18.dp, topEnd = 18.dp),
        tonalElevation = 4.dp,
        modifier = Modifier
            .fillMaxWidth()
            .testTag("slash_command_popup")
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            Text(
                text = "AGENT SKILLS",
                style = MaterialTheme.typography.labelSmall,
                color = TorveCyan,
                modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
            )
            skills.take(5).forEach { skill ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(12.dp))
                        .clickable { onSelectSkill(skill) }
                        .padding(horizontal = 10.dp, vertical = 8.dp)
                        .testTag("slash_command_item_${skill.name}"),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Surface(
                        shape = RoundedCornerShape(8.dp),
                        color = TorveIndigo.copy(alpha = 0.22f)
                    ) {
                        Text(
                            text = skill.primarySlashCommand,
                            style = MaterialTheme.typography.labelMedium,
                            fontFamily = JetBrainsMonoFamily,
                            color = TorveCyan,
                            fontWeight = FontWeight.Bold,
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                        )
                    }
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = skill.name,
                            style = MaterialTheme.typography.labelLarge,
                            color = MaterialTheme.colorScheme.onSurface,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        Text(
                            text = skill.description,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun ActiveSkillPill(
    skillName: String,
    onClear: (() -> Unit)? = null,
    modifier: Modifier = Modifier
) {
    Surface(
        shape = RoundedCornerShape(999.dp),
        color = TorveIndigo.copy(alpha = 0.18f),
        border = BorderStroke(1.dp, TorveCyan.copy(alpha = 0.5f)),
        modifier = modifier.testTag("active_skill_pill_$skillName")
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(5.dp)
        ) {
            Icon(
                imageVector = Icons.Filled.Bolt,
                contentDescription = null,
                tint = TorveCyan,
                modifier = Modifier.size(14.dp)
            )
            Text(
                text = "Skill: $skillName",
                style = MaterialTheme.typography.labelSmall,
                fontFamily = JetBrainsMonoFamily,
                color = TorveCyan,
                fontWeight = FontWeight.SemiBold
            )
            if (onClear != null) {
                Icon(
                    imageVector = Icons.Filled.Close,
                    contentDescription = "Clear active skill",
                    tint = TorveCyan,
                    modifier = Modifier
                        .size(14.dp)
                        .clickable(onClick = onClear)
                )
            }
        }
    }
}

@Composable
private fun ChatMessageBubble(
    message: MessageEntity,
    toolLogs: List<ToolCallLogEntity>,
    activeSkillName: String? = null
) {
    val isUser = message.role == "user"

    if (isUser) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.End
        ) {
            Surface(
                shape = RoundedCornerShape(20.dp, 20.dp, 6.dp, 20.dp),
                color = MaterialTheme.colorScheme.primaryContainer,
                modifier = Modifier.widthIn(max = 320.dp)
            ) {
                Text(
                    text = message.content,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onPrimaryContainer,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp)
                )
            }
        }
    } else {
        Surface(
            shape = RoundedCornerShape(18.dp),
            color = MaterialTheme.colorScheme.surfaceContainerLow,
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(
                modifier = Modifier.padding(14.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Box(
                            modifier = Modifier
                                .size(24.dp)
                                .clip(CircleShape)
                                .background(TorveIndigo.copy(alpha = 0.22f)),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                text = "T",
                                style = MaterialTheme.typography.labelSmall,
                                color = TorveCyan,
                                fontWeight = FontWeight.Bold
                            )
                        }
                        Text(
                            text = "Torve AI",
                            style = MaterialTheme.typography.labelLarge,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                        if (!activeSkillName.isNullOrBlank()) {
                            ActiveSkillPill(skillName = activeSkillName)
                        }
                    }

                    if (message.promptTokens > 0 || message.completionTokens > 0) {
                        Text(
                            text = "${message.promptTokens + message.completionTokens} tok",
                            style = MaterialTheme.typography.labelSmall,
                            fontFamily = JetBrainsMonoFamily,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }

                // Collapsible Reasoning Disclosure
                if (!message.reasoningContent.isNullOrBlank()) {
                    ReasoningDisclosureCard(
                        reasoning = message.reasoningContent,
                        isStreaming = message.isStreaming && message.content.isBlank()
                    )
                }

                // Collapsible Tool Calls attached to this turn
                toolLogs.forEach { log ->
                    CollapsibleToolCallCard(log = log)
                }

                // Assistant Markdown Content
                if (message.content.isNotBlank()) {
                    MarkdownMessageContent(markdown = message.content)
                } else if (message.isStreaming && toolLogs.isEmpty() && message.reasoningContent.isNullOrBlank()) {
                    Text(
                        text = "Streaming response...",
                        style = MaterialTheme.typography.bodySmall,
                        color = TorveCyan
                    )
                }
            }
        }
    }
}

@Composable
private fun ChatStarterPrompts(onPickPrompt: (String) -> Unit) {
    val starters = listOf(
        "/web Build a responsive portfolio website with HTML/CSS/JS, start a local server, and preview it live.",
        "/debug Inspect the workspace and diagnose any broken markup, styling, or scripts.",
        "/explain Map out the sandbox workspace structure and summarize its files."
    )

    Column(
        modifier = Modifier
            .fillMaxSize()
            .widthIn(max = 600.dp)
            .padding(20.dp),
        verticalArrangement = Arrangement.Center
    ) {
        Text(
            text = "Isolated Workspace Ready",
            style = MaterialTheme.typography.headlineSmall,
            color = MaterialTheme.colorScheme.onSurface
        )
        Spacer(Modifier.height(6.dp))
        Text(
            text = "Type / to trigger a modular Agent Skill, or ask Torve AI to write files, run shell commands, and boot a live localhost preview.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(Modifier.height(18.dp))
        starters.forEach { prompt ->
            Surface(
                shape = RoundedCornerShape(14.dp),
                color = MaterialTheme.colorScheme.surfaceContainer,
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 5.dp)
                    .clickable { onPickPrompt(prompt) }
            ) {
                Text(
                    text = prompt,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.padding(14.dp)
                )
            }
        }
    }
}
