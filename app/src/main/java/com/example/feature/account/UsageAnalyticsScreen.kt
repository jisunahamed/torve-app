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
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.example.R
import com.example.feature.auth.AccountSessionState
import com.example.ui.theme.JetBrainsMonoFamily
import com.example.ui.theme.TorveAmber
import com.example.ui.theme.TorveCyan
import com.example.ui.theme.TorveEmerald
import com.example.ui.theme.TorveIndigo
import java.text.NumberFormat

/**
 * Usage & Plan Analytics screen powered by GET /api/desktop/me and GET /api/desktop/usage.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun UsageAnalyticsScreen(
    sessionState: AccountSessionState,
    onSelectRangeAndScope: (range: String, scope: String) -> Unit,
    onRefresh: () -> Unit,
    onNavigateBack: () -> Unit
) {
    val numFmt = remember { NumberFormat.getIntegerInstance() }
    val ranges = listOf("24h", "7d", "30d")
    val scopes = listOf("account" to "Account", "key" to "This Key")

    LaunchedEffect(Unit) {
        onRefresh()
    }

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
                        modifier = Modifier.testTag("usage_back_button")
                    ) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.cd_back))
                    }
                },
                title = { Text("Usage & Plan") },
                actions = {
                    IconButton(
                        onClick = onRefresh,
                        modifier = Modifier.testTag("usage_refresh_button")
                    ) {
                        Icon(Icons.Filled.Refresh, contentDescription = "Refresh usage")
                    }
                }
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
            // Plan & Daily Quota Card from /api/desktop/me
            val me = sessionState.me
            Surface(
                shape = RoundedCornerShape(18.dp),
                color = MaterialTheme.colorScheme.surfaceContainer,
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column {
                            Text(
                                text = me?.user?.name?.ifBlank { null } ?: sessionState.credentials?.userName ?: "Torve Account",
                                style = MaterialTheme.typography.titleMedium
                            )
                            Text(
                                text = me?.user?.email?.ifBlank { null } ?: sessionState.credentials?.userEmail ?: "API Key Session",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        Surface(
                            shape = RoundedCornerShape(999.dp),
                            color = MaterialTheme.colorScheme.primaryContainer
                        ) {
                            Text(
                                text = (me?.user?.plan ?: sessionState.credentials?.userPlan ?: "active").uppercase(),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onPrimaryContainer,
                                modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp)
                            )
                        }
                    }

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        MetricTile(
                            label = "Tokens Today",
                            value = numFmt.format(me?.quota?.usedTokensToday ?: 0L),
                            modifier = Modifier.weight(1f)
                        )
                        MetricTile(
                            label = "Requests Today",
                            value = numFmt.format(me?.quota?.usedRequestsToday ?: 0),
                            modifier = Modifier.weight(1f)
                        )
                        MetricTile(
                            label = "Rate Limit",
                            value = (me?.quota?.rateLimitStatus ?: "ok").uppercase(),
                            accent = if (me?.quota?.rateLimitStatus == "limited") TorveAmber else TorveEmerald,
                            modifier = Modifier.weight(1f)
                        )
                    }

                    val keyInfo = me?.key
                    if (!keyInfo?.prefix.isNullOrBlank()) {
                        Text(
                            text = "Active Key: ${keyInfo.name.ifBlank { "Android" }} (${keyInfo.prefix})",
                            style = MaterialTheme.typography.labelSmall,
                            fontFamily = JetBrainsMonoFamily,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }

            // Range & Scope Selector
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    ranges.forEach { range ->
                        FilterChip(
                            selected = sessionState.usageRange == range,
                            onClick = { onSelectRangeAndScope(range, sessionState.usageScope) },
                            label = { Text(range) },
                            modifier = Modifier.testTag("usage_range_$range")
                        )
                    }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    scopes.forEach { (key, label) ->
                        FilterChip(
                            selected = sessionState.usageScope == key,
                            onClick = { onSelectRangeAndScope(sessionState.usageRange, key) },
                            label = { Text(label) },
                            modifier = Modifier.testTag("usage_scope_$key")
                        )
                    }
                }
            }

            // Totals Card from /api/desktop/usage
            val usage = sessionState.usage
            val totals = usage?.totals
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
                    Text(
                        text = "Period Totals (${usage?.range ?: sessionState.usageRange})",
                        style = MaterialTheme.typography.titleMedium
                    )
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        MetricTile("Requests", numFmt.format(totals?.requests ?: 0), Modifier.weight(1f))
                        MetricTile("Total Tokens", numFmt.format(totals?.totalTokens ?: 0L), Modifier.weight(1f), TorveCyan)
                    }
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        MetricTile("Input Tokens", numFmt.format(totals?.inputTokens ?: 0L), Modifier.weight(1f))
                        MetricTile("Output Tokens", numFmt.format(totals?.outputTokens ?: 0L), Modifier.weight(1f))
                    }
                }
            }

            // Breakdown by Model
            Text("Usage by Model", style = MaterialTheme.typography.titleMedium)
            val models = usage?.byModel.orEmpty()
            if (models.isEmpty()) {
                Text(
                    text = "No model usage recorded in this window.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            } else {
                val maxTokens = (models.maxOfOrNull { it.totalTokens } ?: 1L).coerceAtLeast(1L)
                models.forEach { item ->
                    Surface(
                        shape = RoundedCornerShape(14.dp),
                        color = MaterialTheme.colorScheme.surfaceContainer,
                        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(
                            modifier = Modifier.padding(14.dp),
                            verticalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Text(
                                    text = item.modelId,
                                    style = MaterialTheme.typography.titleSmall,
                                    fontFamily = JetBrainsMonoFamily
                                )
                                Text(
                                    text = "${item.avgLatencyMs}ms avg",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = TorveCyan
                                )
                            }
                            LinearProgressIndicator(
                                progress = { (item.totalTokens.toFloat() / maxTokens.toFloat()).coerceIn(0.05f, 1f) },
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(6.dp)
                                    .clip(RoundedCornerShape(999.dp)),
                                color = TorveIndigo
                            )
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Text(
                                    text = "${numFmt.format(item.requests)} reqs",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                                Text(
                                    text = "${numFmt.format(item.totalTokens)} tokens",
                                    style = MaterialTheme.typography.labelSmall,
                                    fontFamily = JetBrainsMonoFamily
                                )
                            }
                        }
                    }
                }
            }

            // Breakdown by Client App
            Text("Usage by Client App", style = MaterialTheme.typography.titleMedium)
            val apps = usage?.byApp.orEmpty()
            if (apps.isEmpty()) {
                Text(
                    text = "No client app breakdown available yet.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            } else {
                apps.forEach { app ->
                    Surface(
                        shape = RoundedCornerShape(14.dp),
                        color = MaterialTheme.colorScheme.surfaceContainer,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(14.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = app.appId,
                                style = MaterialTheme.typography.titleSmall,
                                fontFamily = JetBrainsMonoFamily
                            )
                            Text(
                                text = "${numFmt.format(app.requests)} reqs • ${numFmt.format(app.totalTokens)} tok",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
            }

            Spacer(Modifier.height(24.dp))
        }
    }
}

@Composable
private fun MetricTile(
    label: String,
    value: String,
    modifier: Modifier = Modifier,
    accent: androidx.compose.ui.graphics.Color = MaterialTheme.colorScheme.onSurface
) {
    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Text(
            text = value,
            style = MaterialTheme.typography.titleMedium,
            fontFamily = JetBrainsMonoFamily,
            fontWeight = FontWeight.Bold,
            color = accent
        )
    }
}
