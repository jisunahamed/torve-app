package com.example.feature.skills

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.R
import com.example.ui.theme.JetBrainsMonoFamily
import com.example.ui.theme.TorveCyan
import com.example.ui.theme.TorveEmerald
import com.example.ui.theme.TorveIndigo
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private val AVAILABLE_HARNESS_TOOLS = listOf(
    "write_file",
    "edit_file",
    "read_file",
    "list_files",
    "grep_search",
    "bash_exec",
    "manage_dev_server"
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SkillsManagerScreen(
    skillRegistry: SkillRegistry,
    onNavigateBack: () -> Unit
) {
    val skills by skillRegistry.skills.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    val snackbarHostState = remember { SnackbarHostState() }

    var editingSkill by remember { mutableStateOf<AgentSkill?>(null) }
    var showCreateSheet by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        withContext(Dispatchers.IO) {
            skillRegistry.ensureStarterSkillsInstalled()
            skillRegistry.syncDisabledStateAndRefresh()
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
                        modifier = Modifier.testTag("skills_back_button")
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
                            text = "Agent Skills",
                            style = MaterialTheme.typography.titleLarge
                        )
                        val enabledCount = skills.count { it.enabled }
                        Text(
                            text = "$enabledCount of ${skills.size} enabled • SKILL.md",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            )
        },
        floatingActionButton = {
            ExtendedFloatingActionButton(
                onClick = { showCreateSheet = true },
                icon = { Icon(Icons.Filled.Add, contentDescription = null) },
                text = { Text("New Skill") },
                containerColor = MaterialTheme.colorScheme.primaryContainer,
                contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
                shape = RoundedCornerShape(18.dp),
                modifier = Modifier.testTag("new_skill_fab")
            )
        }
    ) { innerPadding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding),
            contentAlignment = Alignment.TopCenter
        ) {
            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .widthIn(max = 760.dp)
                    .testTag("skills_list"),
                contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 96.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                item(key = "skills_info_banner") {
                    Surface(
                        shape = RoundedCornerShape(16.dp),
                        color = MaterialTheme.colorScheme.surfaceContainer,
                        border = BorderStroke(1.dp, TorveCyan.copy(alpha = 0.28f)),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Row(
                            modifier = Modifier.padding(14.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(12.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Filled.AutoAwesome,
                                contentDescription = null,
                                tint = TorveCyan,
                                modifier = Modifier.size(22.dp)
                            )
                            Text(
                                text = "Type / in the chat composer (e.g. /web or /debug) to invoke a skill immediately, or let Torve AI load skills on demand via load_skill.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }

                items(skills, key = { "${it.scope.name}_${it.name}" }) { skill ->
                    SkillCard(
                        skill = skill,
                        onToggleEnabled = { enabled ->
                            scope.launch {
                                withContext(Dispatchers.IO) {
                                    skillRegistry.setSkillEnabled(skill.name, enabled)
                                }
                            }
                        },
                        onInspectEdit = { editingSkill = skill }
                    )
                }
            }
        }
    }

    editingSkill?.let { skill ->
        SkillMarkdownEditorDialog(
            skill = skill,
            onDismiss = { editingSkill = null },
            onSave = { updatedMarkdown ->
                scope.launch {
                    val result = runCatching {
                        withContext(Dispatchers.IO) {
                            skillRegistry.saveRawSkillMarkdown(skill, updatedMarkdown)
                        }
                    }
                    editingSkill = null
                    result.onSuccess {
                        snackbarHostState.showSnackbar("Saved ${skill.name}/SKILL.md")
                    }.onFailure { err ->
                        snackbarHostState.showSnackbar(err.message ?: "Failed to save SKILL.md")
                    }
                }
            },
            onDelete = if (!skill.isBuiltIn || skill.scope == SkillScope.WORKSPACE) {
                {
                    scope.launch {
                        val deleted = withContext(Dispatchers.IO) {
                            skillRegistry.deleteSkill(skill)
                        }
                        editingSkill = null
                        if (deleted) {
                            snackbarHostState.showSnackbar("Deleted skill ${skill.name}")
                        }
                    }
                }
            } else {
                null
            }
        )
    }

    if (showCreateSheet) {
        ModalBottomSheet(
            onDismissRequest = { showCreateSheet = false },
            containerColor = MaterialTheme.colorScheme.surfaceContainerLow
        ) {
            CreateSkillSheetContent(
                onCancel = { showCreateSheet = false },
                onCreate = { name, description, triggers, tools, instructions ->
                    scope.launch {
                        val result = runCatching {
                            withContext(Dispatchers.IO) {
                                skillRegistry.saveCustomSkill(
                                    name = name,
                                    description = description,
                                    triggers = triggers,
                                    allowedTools = tools,
                                    instructions = instructions
                                )
                            }
                        }
                        showCreateSheet = false
                        result.onSuccess { created ->
                            snackbarHostState.showSnackbar("Created skill ${created.primarySlashCommand}")
                        }.onFailure { err ->
                            snackbarHostState.showSnackbar(err.message ?: "Could not create skill")
                        }
                    }
                }
            )
        }
    }
}

@Composable
private fun SkillCard(
    skill: AgentSkill,
    onToggleEnabled: (Boolean) -> Unit,
    onInspectEdit: () -> Unit
) {
    Surface(
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        border = BorderStroke(
            1.dp,
            if (skill.enabled) TorveIndigo.copy(alpha = 0.35f) else MaterialTheme.colorScheme.outlineVariant
        ),
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onInspectEdit)
            .testTag("skill_card_${skill.name}")
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.weight(1f)
                ) {
                    Text(
                        text = skill.name,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onSurface,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Surface(
                        shape = RoundedCornerShape(999.dp),
                        color = TorveCyan.copy(alpha = 0.16f)
                    ) {
                        Text(
                            text = skill.primarySlashCommand,
                            style = MaterialTheme.typography.labelSmall,
                            fontFamily = JetBrainsMonoFamily,
                            color = TorveCyan,
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp)
                        )
                    }
                    Surface(
                        shape = RoundedCornerShape(999.dp),
                        color = if (skill.scope == SkillScope.WORKSPACE) {
                            TorveEmerald.copy(alpha = 0.16f)
                        } else {
                            MaterialTheme.colorScheme.surfaceContainerHigh
                        }
                    ) {
                        Text(
                            text = skill.scope.name,
                            style = MaterialTheme.typography.labelSmall,
                            color = if (skill.scope == SkillScope.WORKSPACE) {
                                TorveEmerald
                            } else {
                                MaterialTheme.colorScheme.onSurfaceVariant
                            },
                            modifier = Modifier.padding(horizontal = 7.dp, vertical = 2.dp)
                        )
                    }
                }

                Switch(
                    checked = skill.enabled,
                    onCheckedChange = onToggleEnabled,
                    modifier = Modifier.testTag("skill_toggle_${skill.name}")
                )
            }

            Text(
                text = skill.description,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                skill.allowedTools.forEach { tool ->
                    Surface(
                        shape = RoundedCornerShape(8.dp),
                        color = MaterialTheme.colorScheme.surfaceContainerHigh
                    ) {
                        Text(
                            text = tool,
                            style = MaterialTheme.typography.labelSmall,
                            fontFamily = JetBrainsMonoFamily,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp)
                        )
                    }
                }
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = if (skill.isBuiltIn) "Built-in starter skill" else "Custom SKILL.md",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    Icon(
                        imageVector = Icons.Filled.Edit,
                        contentDescription = null,
                        tint = TorveCyan,
                        modifier = Modifier.size(14.dp)
                    )
                    Text(
                        text = "Inspect / Edit SKILL.md",
                        style = MaterialTheme.typography.labelSmall,
                        color = TorveCyan
                    )
                }
            }
        }
    }
}

@Composable
private fun SkillMarkdownEditorDialog(
    skill: AgentSkill,
    onDismiss: () -> Unit,
    onSave: (String) -> Unit,
    onDelete: (() -> Unit)?
) {
    var markdownText by remember(skill.filePath, skill.rawMarkdown) {
        mutableStateOf(skill.rawMarkdown)
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "${skill.name}/SKILL.md",
                        style = MaterialTheme.typography.titleMedium,
                        fontFamily = JetBrainsMonoFamily
                    )
                    Text(
                        text = skill.primarySlashCommand,
                        style = MaterialTheme.typography.labelSmall,
                        color = TorveCyan
                    )
                }
                if (onDelete != null) {
                    IconButton(
                        onClick = onDelete,
                        modifier = Modifier.testTag("delete_skill_button")
                    ) {
                        Icon(
                            imageVector = Icons.Filled.Delete,
                            contentDescription = "Delete custom skill",
                            tint = MaterialTheme.colorScheme.error
                        )
                    }
                }
            }
        },
        text = {
            OutlinedTextField(
                value = markdownText,
                onValueChange = { markdownText = it },
                textStyle = MaterialTheme.typography.bodySmall.copy(
                    fontFamily = JetBrainsMonoFamily,
                    fontSize = 12.sp,
                    lineHeight = 18.sp
                ),
                minLines = 10,
                maxLines = 16,
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 240.dp, max = 360.dp)
                    .testTag("skill_markdown_editor")
            )
        },
        confirmButton = {
            Button(
                onClick = { onSave(markdownText) },
                enabled = markdownText.isNotBlank(),
                modifier = Modifier.testTag("save_skill_markdown_button")
            ) {
                Text("Save SKILL.md")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel")
            }
        }
    )
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun CreateSkillSheetContent(
    onCancel: () -> Unit,
    onCreate: (name: String, description: String, triggers: List<String>, tools: List<String>, instructions: String) -> Unit
) {
    var name by remember { mutableStateOf("") }
    var description by remember { mutableStateOf("") }
    var triggersText by remember { mutableStateOf("") }
    var selectedTools by remember { mutableStateOf(setOf("write_file", "edit_file", "read_file", "bash_exec")) }
    var instructions by remember {
        mutableStateOf(
            "# Instructions\n1. Inspect the workspace first.\n2. Implement the requested task cleanly.\n3. Verify the result and summarize concisely."
        )
    }

    val slugPreview = remember(name) {
        if (name.isBlank()) "my-skill" else SkillFrontmatterParser.sanitizeSlug(name)
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Text(
            text = "Create Agent Skill",
            style = MaterialTheme.typography.titleLarge
        )
        Text(
            text = "Saved as skills/$slugPreview/SKILL.md inside the on-device sandbox.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )

        OutlinedTextField(
            value = name,
            onValueChange = { name = it },
            label = { Text("Skill Name (slug)") },
            placeholder = { Text("e.g. react-component") },
            singleLine = true,
            shape = RoundedCornerShape(14.dp),
            modifier = Modifier
                .fillMaxWidth()
                .testTag("create_skill_name_input")
        )

        OutlinedTextField(
            value = description,
            onValueChange = { description = it },
            label = { Text("Description") },
            placeholder = { Text("What does this skill specialize in?") },
            maxLines = 2,
            shape = RoundedCornerShape(14.dp),
            modifier = Modifier
                .fillMaxWidth()
                .testTag("create_skill_description_input")
        )

        OutlinedTextField(
            value = triggersText,
            onValueChange = { triggersText = it },
            label = { Text("Triggers (comma-separated)") },
            placeholder = { Text("/$slugPreview, build component") },
            singleLine = true,
            shape = RoundedCornerShape(14.dp),
            modifier = Modifier
                .fillMaxWidth()
                .testTag("create_skill_triggers_input")
        )

        Text(
            text = "Allowed Tools",
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurface
        )

        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            AVAILABLE_HARNESS_TOOLS.forEach { tool ->
                val isSelected = tool in selectedTools
                FilterChip(
                    selected = isSelected,
                    onClick = {
                        selectedTools = if (isSelected) selectedTools - tool else selectedTools + tool
                    },
                    label = {
                        Text(
                            text = tool,
                            fontFamily = JetBrainsMonoFamily,
                            style = MaterialTheme.typography.labelSmall
                        )
                    },
                    colors = FilterChipDefaults.filterChipColors(
                        selectedContainerColor = MaterialTheme.colorScheme.primaryContainer,
                        selectedLabelColor = MaterialTheme.colorScheme.onPrimaryContainer
                    )
                )
            }
        }

        OutlinedTextField(
            value = instructions,
            onValueChange = { instructions = it },
            label = { Text("SKILL.md Instructions (Markdown)") },
            minLines = 5,
            maxLines = 8,
            textStyle = MaterialTheme.typography.bodySmall.copy(fontFamily = JetBrainsMonoFamily),
            shape = RoundedCornerShape(14.dp),
            modifier = Modifier
                .fillMaxWidth()
                .testTag("create_skill_instructions_input")
        )

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 4.dp, bottom = 24.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            OutlinedButton(
                onClick = onCancel,
                modifier = Modifier.weight(1f)
            ) {
                Text("Cancel")
            }
            Button(
                onClick = {
                    val parsedTriggers = triggersText.split(",")
                        .map { it.trim() }
                        .filter { it.isNotBlank() }
                        .ifEmpty { listOf("/$slugPreview") }
                    onCreate(
                        name.trim(),
                        description.trim().ifBlank { "Custom agent skill ($slugPreview)" },
                        parsedTriggers,
                        selectedTools.toList(),
                        instructions.trim()
                    )
                },
                enabled = name.isNotBlank() && instructions.isNotBlank(),
                modifier = Modifier
                    .weight(1f)
                    .testTag("create_skill_submit_button")
            ) {
                Text("Create Skill")
            }
        }
    }
}
