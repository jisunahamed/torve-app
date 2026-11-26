package com.example.feature.preview

import android.annotation.SuppressLint
import android.net.Uri
import android.webkit.ConsoleMessage
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.animation.AnimatedVisibility
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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.BugReport
import androidx.compose.material.icons.filled.DesktopWindows
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Smartphone
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.R
import com.example.core.TorveContainer
import com.example.ui.theme.JetBrainsMonoFamily
import com.example.ui.theme.TorveAmber
import com.example.ui.theme.TorveCodeBgDark
import com.example.ui.theme.TorveCyan
import com.example.ui.theme.TorveEmerald
import com.example.ui.theme.TorveRose

data class WebConsoleEntry(
    val level: String,
    val message: String,
    val source: String
)

/**
 * In-app localhost WebView Live Preview strictly confined to 127.0.0.1 / localhost,
 * featuring mobile/desktop viewport toggle, server quick-start/stop, and JS console inspector.
 */
@OptIn(ExperimentalMaterial3Api::class)
@SuppressLint("SetJavaScriptEnabled")
@Composable
fun LivePreviewScreen(
    workspaceId: String,
    initialPort: Int,
    container: TorveContainer,
    onNavigateBack: () -> Unit
) {
    val serversMap by container.devServerManager.runningServers.collectAsStateWithLifecycle()
    val runningServer = serversMap[workspaceId]
    val activePort = runningServer?.port ?: initialPort
    val previewUrl = "http://127.0.0.1:$activePort/"

    var currentUrl by remember(previewUrl) { mutableStateOf(previewUrl) }
    var desktopMode by remember { mutableStateOf(false) }
    var showConsole by remember { mutableStateOf(false) }
    var loadProgress by remember { mutableIntStateOf(100) }
    var webViewRef by remember { mutableStateOf<WebView?>(null) }
    val consoleLogs = remember { mutableStateListOf<WebConsoleEntry>() }

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
                        modifier = Modifier.testTag("preview_back_button")
                    ) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(R.string.cd_back)
                        )
                    }
                },
                title = {
                    Surface(
                        shape = RoundedCornerShape(999.dp),
                        color = MaterialTheme.colorScheme.surfaceContainerHigh,
                        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            Text(
                                text = if (runningServer != null) "●" else "○",
                                color = if (runningServer != null) TorveEmerald else TorveAmber,
                                fontSize = 11.sp
                            )
                            Text(
                                text = currentUrl.removePrefix("http://"),
                                style = MaterialTheme.typography.labelSmall,
                                fontFamily = JetBrainsMonoFamily,
                                color = MaterialTheme.colorScheme.onSurface,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                    }
                },
                actions = {
                    IconButton(
                        onClick = {
                            desktopMode = !desktopMode
                            webViewRef?.let { wv ->
                                applyViewportMode(wv.settings, desktopMode)
                                wv.reload()
                            }
                        },
                        modifier = Modifier.testTag("preview_viewport_toggle")
                    ) {
                        Icon(
                            imageVector = if (desktopMode) Icons.Filled.DesktopWindows else Icons.Filled.Smartphone,
                            contentDescription = if (desktopMode) "Switch to mobile viewport" else "Switch to desktop viewport",
                            tint = TorveCyan
                        )
                    }

                    IconButton(
                        onClick = { showConsole = !showConsole },
                        modifier = Modifier.testTag("preview_console_toggle")
                    ) {
                        Icon(
                            imageVector = Icons.Filled.BugReport,
                            contentDescription = "Toggle JS console inspector",
                            tint = if (showConsole) TorveAmber else MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }

                    IconButton(
                        onClick = { webViewRef?.reload() },
                        modifier = Modifier.testTag("preview_refresh_button")
                    ) {
                        Icon(
                            imageVector = Icons.Filled.Refresh,
                            contentDescription = "Reload preview"
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
        ) {
            if (loadProgress in 1..99) {
                LinearProgressIndicator(
                    progress = { loadProgress / 100f },
                    modifier = Modifier.fillMaxWidth(),
                    color = TorveCyan
                )
            }

            // Server status control bar
            Surface(
                color = MaterialTheme.colorScheme.surfaceContainer,
                modifier = Modifier.fillMaxWidth()
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 14.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(
                        text = if (runningServer != null) {
                            "Serving localhost:${runningServer.port} (${runningServer.mode})"
                        } else {
                            "Server stopped — tap Start to serve workspace"
                        },
                        style = MaterialTheme.typography.labelSmall,
                        color = if (runningServer != null) TorveEmerald else MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Surface(
                        shape = RoundedCornerShape(999.dp),
                        color = if (runningServer != null) {
                            MaterialTheme.colorScheme.errorContainer
                        } else {
                            TorveEmerald.copy(alpha = 0.2f)
                        },
                        modifier = Modifier
                            .clickable {
                                if (runningServer != null) {
                                    container.devServerManager.stopServer(workspaceId)
                                } else {
                                    val dir = container.sandboxBootstrap.ensureWorkspaceDir(workspaceId)
                                    val info = container.devServerManager.startServer(
                                        workspaceId = workspaceId,
                                        workspaceName = workspaceId,
                                        workspaceDir = dir,
                                        requestedCommand = null,
                                        requestedPort = activePort
                                    )
                                    currentUrl = info.previewUrl
                                    webViewRef?.loadUrl(info.previewUrl)
                                }
                            }
                            .testTag("preview_server_toggle")
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(4.dp)
                        ) {
                            Icon(
                                imageVector = if (runningServer != null) Icons.Filled.Stop else Icons.Filled.PlayArrow,
                                contentDescription = null,
                                tint = if (runningServer != null) MaterialTheme.colorScheme.error else TorveEmerald,
                                modifier = Modifier.size(14.dp)
                            )
                            Text(
                                text = if (runningServer != null) "Stop" else "Start",
                                style = MaterialTheme.typography.labelSmall,
                                color = if (runningServer != null) MaterialTheme.colorScheme.error else TorveEmerald
                            )
                        }
                    }
                }
            }

            // WebView confined strictly to 127.0.0.1 / localhost
            AndroidView(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .testTag("localhost_webview"),
                factory = { ctx ->
                    WebView(ctx).apply {
                        settings.javaScriptEnabled = true
                        settings.domStorageEnabled = true
                        settings.allowFileAccess = false
                        settings.allowContentAccess = false
                        applyViewportMode(settings, desktopMode)

                        webViewClient = object : WebViewClient() {
                            override fun shouldOverrideUrlLoading(
                                view: WebView?,
                                request: WebResourceRequest?
                            ): Boolean {
                                val target = request?.url ?: return true
                                val host = target.host?.lowercase()
                                // Security guardrail: strictly allow loopback hosts only
                                return host != "127.0.0.1" && host != "localhost"
                            }

                            override fun onPageFinished(view: WebView?, url: String?) {
                                if (!url.isNullOrBlank()) currentUrl = url
                            }
                        }

                        webChromeClient = object : WebChromeClient() {
                            override fun onProgressChanged(view: WebView?, newProgress: Int) {
                                loadProgress = newProgress
                            }

                            override fun onConsoleMessage(consoleMessage: ConsoleMessage?): Boolean {
                                consoleMessage?.let { cm ->
                                    val entry = WebConsoleEntry(
                                        level = cm.messageLevel().name,
                                        message = cm.message().orEmpty(),
                                        source = "${Uri.parse(cm.sourceId().orEmpty()).lastPathSegment ?: "page"}:${cm.lineNumber()}"
                                    )
                                    consoleLogs.add(entry)
                                    if (consoleLogs.size > 100) consoleLogs.removeAt(0)
                                }
                                return true
                            }
                        }
                        webViewRef = this
                        loadUrl(previewUrl)
                    }
                },
                update = { wv ->
                    webViewRef = wv
                }
            )

            // Expandable JS Console Inspector
            AnimatedVisibility(visible = showConsole) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(190.dp)
                        .background(TorveCodeBgDark)
                ) {
                    HorizontalDivider(color = Color(0xFF243047))
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 12.dp, vertical = 6.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "JS CONSOLE (${consoleLogs.size})",
                            style = MaterialTheme.typography.labelSmall,
                            color = TorveCyan
                        )
                        Text(
                            text = "Clear",
                            style = MaterialTheme.typography.labelSmall,
                            color = Color(0xFF94A3B8),
                            modifier = Modifier.clickable { consoleLogs.clear() }
                        )
                    }
                    LazyColumn(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(horizontal = 12.dp, vertical = 4.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        if (consoleLogs.isEmpty()) {
                            item {
                                Text(
                                    text = "No console messages captured yet.",
                                    fontFamily = JetBrainsMonoFamily,
                                    fontSize = 11.5.sp,
                                    color = Color(0xFF64748B)
                                )
                            }
                        } else {
                            items(consoleLogs) { entry ->
                                val color = when (entry.level) {
                                    "ERROR" -> TorveRose
                                    "WARNING" -> TorveAmber
                                    else -> Color(0xFFE2E8F0)
                                }
                                Text(
                                    text = "[${entry.level}] ${entry.message} (${entry.source})",
                                    fontFamily = JetBrainsMonoFamily,
                                    fontSize = 11.sp,
                                    color = color
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

private fun applyViewportMode(settings: WebSettings, desktopMode: Boolean) {
    settings.useWideViewPort = desktopMode
    settings.loadWithOverviewMode = desktopMode
    settings.userAgentString = if (desktopMode) {
        "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/128.0.0.0 Safari/537.36"
    } else {
        null
    }
}
