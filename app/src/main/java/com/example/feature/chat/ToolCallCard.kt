package com.example.feature.chat

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Code
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Dns
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Error
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.Psychology
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Shield
import androidx.compose.material.icons.filled.Terminal
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.core.database.ToolCallLogEntity
import com.example.core.database.ToolCallStatus
import com.example.core.network.TorveModel
import com.example.feature.harness.PendingShellApproval
import com.example.ui.theme.JetBrainsMonoFamily
import com.example.ui.theme.TorveAmber
import com.example.ui.theme.TorveCodeBgDark
import com.example.ui.theme.TorveCyan
import com.example.ui.theme.TorveEmerald
import com.example.ui.theme.TorveIndigo
import com.example.ui.theme.TorveRose

/**
 * Collapsible Thinking / Reasoning block for `delta.reasoning_content`.
 */
@Composable
fun ReasoningDisclosureCard(
    reasoning: String,
    isStreaming: Boolean,
    modifier: Modifier = Modifier
) {
    var expanded by remember { mutableStateOf(false) }

    Surface(
        shape = RoundedCornerShape(14.dp),
        color = MaterialTheme.colorScheme.surfaceContainer,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        modifier = modifier.fillMaxWidth()
    ) {
        Column(modifier = Modifier.fillMaxWidth()) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { expanded = !expanded }
                    .padding(horizontal = 12.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    if (isStreaming) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(14.dp),
                            strokeWidth = 2.dp,
                            color = TorveCyan
                        )
                    } else {
                        Icon(
                            imageVector = Icons.Filled.Psychology,
                            contentDescription = null,
                            tint = TorveCyan,
                            modifier = Modifier.size(16.dp)
                        )
                    }
                    Text(
                        text = if (isStreaming) "Thinking..." else "Reasoning (${reasoning.length} chars)",
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Icon(
                    imageVector = if (expanded) Icons.Filled.ExpandLess else Icons.Filled.ExpandMore,
                    contentDescription = if (expanded) "Collapse reasoning" else "Expand reasoning",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(18.dp)
                )
            }

            AnimatedVisibility(visible = expanded) {
                Column {
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                    Text(
                        text = reasoning,
                        style = MaterialTheme.typography.bodySmall,
                        fontFamily = JetBrainsMonoFamily,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(12.dp)
                    )
                }
            }
        }
    }
}

/**
 * Gmail Collapsed-Message style Tool Call execution pill expanding to terminal output or unified diff.
 */
@Composable
fun CollapsibleToolCallCard(
    log: ToolCallLogEntity,
    modifier: Modifier = Modifier
) {
    var expanded by remember(log.id) { mutableStateOf(false) }
    val statusColor = when (log.status) {
        ToolCallStatus.SUCCESS -> TorveEmerald
        ToolCallStatus.ERROR, ToolCallStatus.DENIED -> TorveRose
        ToolCallStatus.PENDING_APPROVAL -> TorveAmber
        else -> TorveCyan
    }

    Surface(
        shape = RoundedCornerShape(14.dp),
        color = MaterialTheme.colorScheme.surfaceContainer,
        border = BorderStroke(1.dp, statusColor.copy(alpha = 0.3f)),
        modifier = modifier
            .fillMaxWidth()
            .testTag("tool_call_card_${log.toolName}")
    ) {
        Column(modifier = Modifier.fillMaxWidth()) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { expanded = !expanded }
                    .padding(horizontal = 12.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Icon(
                    imageVector = iconForTool(log.toolName),
                    contentDescription = null,
                    tint = statusColor,
                    modifier = Modifier.size(18.dp)
                )

                Column(modifier = Modifier.weight(1f)) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        Text(
                            text = log.toolName,
                            style = MaterialTheme.typography.labelLarge,
                            fontFamily = JetBrainsMonoFamily,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                        if (log.durationMs > 0L) {
                            Text(
                                text = "${log.durationMs}ms",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                    if (log.summary.isNotBlank()) {
                        Text(
                            text = log.summary,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }

                when (log.status) {
                    ToolCallStatus.RUNNING -> CircularProgressIndicator(
                        modifier = Modifier.size(16.dp),
                        strokeWidth = 2.dp,
                        color = TorveCyan
                    )
                    ToolCallStatus.SUCCESS -> Icon(
                        imageVector = Icons.Filled.CheckCircle,
                        contentDescription = "Succeeded",
                        tint = TorveEmerald,
                        modifier = Modifier.size(18.dp)
                    )
                    ToolCallStatus.ERROR, ToolCallStatus.DENIED -> Icon(
                        imageVector = Icons.Filled.Error,
                        contentDescription = "Failed",
                        tint = TorveRose,
                        modifier = Modifier.size(18.dp)
                    )
                    else -> Icon(
                        imageVector = Icons.Filled.Shield,
                        contentDescription = "Pending approval",
                        tint = TorveAmber,
                        modifier = Modifier.size(18.dp)
                    )
                }

                Icon(
                    imageVector = if (expanded) Icons.Filled.ExpandLess else Icons.Filled.ExpandMore,
                    contentDescription = if (expanded) "Collapse" else "Expand",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(18.dp)
                )
            }

            AnimatedVisibility(visible = expanded) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(TorveCodeBgDark)
                        .padding(12.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    if (!log.diffContent.isNullOrBlank()) {
                        Text(
                            text = "FILE DIFF",
                            style = MaterialTheme.typography.labelSmall,
                            color = TorveCyan
                        )
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .horizontalScroll(rememberScrollState())
                        ) {
                            log.diffContent.lines().forEach { line ->
                                val lineColor = when {
                                    line.startsWith("+") -> TorveEmerald
                                    line.startsWith("-") -> TorveRose
                                    else -> Color(0xFF94A3B8)
                                }
                                Text(
                                    text = line,
                                    fontFamily = JetBrainsMonoFamily,
                                    fontSize = 11.5.sp,
                                    lineHeight = 16.sp,
                                    color = lineColor
                                )
                            }
                        }
                    }

                    if (log.output.isNotBlank()) {
                        Text(
                            text = "OUTPUT",
                            style = MaterialTheme.typography.labelSmall,
                            color = Color(0xFF94A3B8)
                        )
                        Text(
                            text = log.output,
                            fontFamily = JetBrainsMonoFamily,
                            fontSize = 11.5.sp,
                            lineHeight = 16.sp,
                            color = Color(0xFFE2E8F0),
                            modifier = Modifier
                                .fillMaxWidth()
                                .horizontalScroll(rememberScrollState())
                        )
                    }
                }
            }
        }
    }
}

/**
 * Inline Approval Gate Card when "Ask Before Shell Commands" is enabled.
 */
@Composable
fun ShellApprovalCard(
    approval: PendingShellApproval,
    onApprove: () -> Unit,
    onDeny: () -> Unit,
    modifier: Modifier = Modifier
) {
    Surface(
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        border = BorderStroke(1.dp, TorveAmber.copy(alpha = 0.6f)),
        modifier = modifier
            .fillMaxWidth()
            .testTag("shell_approval_card")
    ) {
        Column(
            modifier = Modifier.padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Icon(
                    imageVector = Icons.Filled.Shield,
                    contentDescription = null,
                    tint = TorveAmber,
                    modifier = Modifier.size(18.dp)
                )
                Text(
                    text = "Approve Shell Command",
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.onSurface
                )
            }

            Surface(
                shape = RoundedCornerShape(10.dp),
                color = TorveCodeBgDark,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(
                    text = "$ ${approval.command}",
                    fontFamily = JetBrainsMonoFamily,
                    fontSize = 12.5.sp,
                    color = TorveCyan,
                    modifier = Modifier.padding(10.dp)
                )
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                OutlinedButton(
                    onClick = onDeny,
                    modifier = Modifier
                        .weight(1f)
                        .testTag("deny_shell_button")
                ) {
                    Text("Deny", color = MaterialTheme.colorScheme.error)
                }
                Button(
                    onClick = onApprove,
                    colors = ButtonDefaults.buttonColors(
                        containerColor = TorveEmerald,
                        contentColor = Color(0xFF032318)
                    ),
                    modifier = Modifier
                        .weight(1f)
                        .testTag("approve_shell_button")
                ) {
                    Text("Allow & Run", fontWeight = FontWeight.Bold)
                }
            }
        }
    }
}

/**
 * 1-tap Model Switcher Bottom Sheet populated from GET /v1/models.
 */
@Composable
fun ModelSwitcherSheetContent(
    models: List<TorveModel>,
    selectedModelId: String,
    onSelectModel: (TorveModel) -> Unit
) {
    var search by remember { mutableStateOf("") }
    val filtered = remember(models, search) {
        val q = search.trim().lowercase()
        if (q.isEmpty()) models else models.filter {
            it.id.lowercase().contains(q) || it.displayName.lowercase().contains(q)
        }
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 20.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Text(
            text = "Select Torve Model",
            style = MaterialTheme.typography.titleLarge
        )

        OutlinedTextField(
            value = search,
            onValueChange = { search = it },
            placeholder = { Text("Filter models...") },
            leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null) },
            singleLine = true,
            shape = RoundedCornerShape(14.dp),
            modifier = Modifier
                .fillMaxWidth()
                .testTag("model_search_input")
        )

        if (filtered.isEmpty()) {
            Text(
                text = "No models available yet. Pull to refresh or check your connection to api.torveai.com.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(vertical = 24.dp)
            )
        } else {
            LazyColumn(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 380.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                items(filtered, key = { it.id }) { model ->
                    val isSelected = model.id == selectedModelId
                    Surface(
                        shape = RoundedCornerShape(14.dp),
                        color = if (isSelected) {
                            MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.55f)
                        } else {
                            MaterialTheme.colorScheme.surfaceContainer
                        },
                        border = BorderStroke(
                            1.dp,
                            if (isSelected) TorveIndigo else MaterialTheme.colorScheme.outlineVariant
                        ),
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { onSelectModel(model) }
                            .testTag("model_item_${model.id}")
                    ) {
                        Column(
                            modifier = Modifier.padding(14.dp),
                            verticalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    text = model.displayName,
                                    style = MaterialTheme.typography.titleMedium,
                                    color = MaterialTheme.colorScheme.onSurface,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                    modifier = Modifier.weight(1f)
                                )
                                model.tier?.takeIf { it.isNotBlank() }?.let { tier ->
                                    Surface(
                                        shape = RoundedCornerShape(999.dp),
                                        color = if (tier.equals("pro", true)) {
                                            TorveIndigo.copy(alpha = 0.22f)
                                        } else {
                                            TorveEmerald.copy(alpha = 0.2f)
                                        }
                                    ) {
                                        Text(
                                            text = tier.uppercase(),
                                            style = MaterialTheme.typography.labelSmall,
                                            color = if (tier.equals("pro", true)) TorveIndigo else TorveEmerald,
                                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp)
                                        )
                                    }
                                }
                            }

                            Row(
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                if (model.supportsTools) {
                                    ModelCapabilityTag(Icons.Filled.Build, "Tools", TorveCyan)
                                }
                                if (model.supportsReasoning) {
                                    ModelCapabilityTag(Icons.Filled.AutoAwesome, "Reasoning", TorveAmber)
                                }
                                model.contextWindow?.let { ctx ->
                                    Text(
                                        text = "${ctx / 1000}k ctx",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
        Spacer(Modifier.height(16.dp))
    }
}

@Composable
private fun ModelCapabilityTag(icon: ImageVector, label: String, color: Color) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(3.dp)
    ) {
        Icon(icon, contentDescription = null, tint = color, modifier = Modifier.size(13.dp))
        Text(text = label, style = MaterialTheme.typography.labelSmall, color = color)
    }
}

private fun iconForTool(toolName: String): ImageVector = when (toolName) {
    "bash_exec" -> Icons.Filled.Terminal
    "read_file" -> Icons.Filled.Description
    "write_file" -> Icons.Filled.Code
    "edit_file" -> Icons.Filled.Edit
    "list_files" -> Icons.Filled.FolderOpen
    "grep_search" -> Icons.Filled.Search
    "manage_dev_server" -> Icons.Filled.Dns
    "load_skill" -> Icons.Filled.AutoAwesome
    else -> Icons.Filled.Build
}
