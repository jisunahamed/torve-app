package com.example.feature.account

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Logout
import androidx.compose.material.icons.filled.StopCircle
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.example.R
import com.example.core.database.AppSettings
import com.example.feature.sandbox.SandboxDiagnostics
import com.example.ui.theme.JetBrainsMonoFamily
import com.example.ui.theme.ThemeMode
import com.example.ui.theme.TorveAmber
import com.example.ui.theme.TorveEmerald
import kotlin.math.roundToInt

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    settings: AppSettings,
    diagnostics: SandboxDiagnostics,
    runningServerCount: Int,
    keyPrefix: String?,
    onSetThemeMode: (ThemeMode) -> Unit,
    onSetAskBeforeShell: (Boolean) -> Unit,
    onSetMaxSteps: (Int) -> Unit,
    onStopAllServers: () -> Unit,
    onSignOutAndRevoke: () -> Unit,
    onNavigateBack: () -> Unit
) {
    Scaffold(
        contentWindowInsets = WindowInsets.safeDrawing,
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            TopAppBar(
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surfaceContainerLow
                ),
                navigationIcon = {
                    IconButton(
                        onClick = onNavigateBack,
                        modifier = Modifier.testTag("settings_back_button")
                    ) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.cd_back))
                    }
                },
                title = { Text("Settings") }
            )
        }
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .verticalScroll(rememberScrollState())
                .padding(16.dp)
                .widthIn(max = 640.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            // Appearance
            SettingsSectionCard(title = "Appearance") {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    ThemeMode.entries.forEach { mode ->
                        FilterChip(
                            selected = settings.themeMode == mode,
                            onClick = { onSetThemeMode(mode) },
                            label = { Text(mode.name.lowercase().replaceFirstChar { it.uppercase() }) },
                            modifier = Modifier.testTag("theme_chip_${mode.name.lowercase()}")
                        )
                    }
                }
            }

            // Agent Harness Guardrails
            SettingsSectionCard(title = "Agentic Harness Guardrails") {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = if (settings.askBeforeShellCommands) {
                                "Ask Before Shell Commands"
                            } else {
                                "Auto-Run (YOLO Mode)"
                            },
                            style = MaterialTheme.typography.titleSmall
                        )
                        Text(
                            text = if (settings.askBeforeShellCommands) {
                                "Shows an inline Approve/Deny prompt before bash_exec executes."
                            } else {
                                "Executes bash_exec automatically inside the jailed workspace."
                            },
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Switch(
                        checked = settings.askBeforeShellCommands,
                        onCheckedChange = onSetAskBeforeShell,
                        modifier = Modifier.testTag("ask_before_shell_switch")
                    )
                }

                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)

                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text("Max Agent Steps per Prompt", style = MaterialTheme.typography.titleSmall)
                        Text(
                            text = "${settings.maxAgentSteps} steps",
                            style = MaterialTheme.typography.labelLarge,
                            fontFamily = JetBrainsMonoFamily,
                            color = MaterialTheme.colorScheme.secondary
                        )
                    }
                    Slider(
                        value = settings.maxAgentSteps.toFloat(),
                        onValueChange = { onSetMaxSteps(it.roundToInt()) },
                        valueRange = 5f..40f,
                        steps = 6,
                        modifier = Modifier.testTag("max_steps_slider")
                    )
                }
            }

            // On-Device Sandbox Diagnostics
            SettingsSectionCard(title = "On-Device Linux Sandbox") {
                DiagnosticRow("Status", if (diagnostics.isBootstrapped) "Bootstrapped & Isolated" else "Initializing", TorveEmerald)
                DiagnosticRow("ABI / Arch", diagnostics.abi)
                DiagnosticRow("Shell Binary", diagnostics.shellBinary)
                DiagnosticRow("PRoot Native Lib", if (diagnostics.prootAvailable) "Bundled (W^X)" else "POSIX Jail Fallback")
                DiagnosticRow("Workspaces", "${diagnostics.workspaceCount} isolated directories")
                DiagnosticRow("Active Dev Servers", "$runningServerCount running", if (runningServerCount > 0) TorveEmerald else MaterialTheme.colorScheme.onSurfaceVariant)

                if (runningServerCount > 0) {
                    OutlinedButton(
                        onClick = onStopAllServers,
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("stop_all_servers_button")
                    ) {
                        Icon(Icons.Filled.StopCircle, contentDescription = null)
                        Spacer(Modifier.width(8.dp))
                        Text("Stop All Local Servers")
                    }
                }
            }

            // Session & Revoke
            SettingsSectionCard(title = "Authentication & Security") {
                DiagnosticRow("Credential Store", "Android Keystore (AES-256-GCM)")
                if (!keyPrefix.isNullOrBlank()) {
                    DiagnosticRow("Active Key Prefix", keyPrefix)
                }
                Text(
                    text = "Signing out calls POST /api/desktop/auth/revoke and clears your local encrypted API key.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                OutlinedButton(
                    onClick = onSignOutAndRevoke,
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.error),
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.error.copy(alpha = 0.5f)),
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("sign_out_revoke_button")
                ) {
                    Icon(Icons.AutoMirrored.Filled.Logout, contentDescription = null)
                    Spacer(Modifier.width(8.dp))
                    Text("Sign Out & Revoke Key")
                }
            }

            Spacer(Modifier.height(24.dp))
        }
    }
}

@Composable
private fun SettingsSectionCard(
    title: String,
    content: @Composable () -> Unit
) {
    Surface(
        shape = RoundedCornerShape(18.dp),
        color = MaterialTheme.colorScheme.surfaceContainer,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text(text = title, style = MaterialTheme.typography.titleMedium)
            content()
        }
    }
}

@Composable
private fun DiagnosticRow(
    label: String,
    value: String,
    valueColor: androidx.compose.ui.graphics.Color = MaterialTheme.colorScheme.onSurface
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Text(
            text = value,
            style = MaterialTheme.typography.labelSmall,
            fontFamily = JetBrainsMonoFamily,
            color = valueColor
        )
    }
}
