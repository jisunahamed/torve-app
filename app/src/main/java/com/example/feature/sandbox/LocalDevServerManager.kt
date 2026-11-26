package com.example.feature.sandbox

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.BufferedInputStream
import java.io.BufferedReader
import java.io.File
import java.io.InputStreamReader
import java.io.OutputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.URLDecoder
import java.nio.charset.StandardCharsets
import java.util.concurrent.ConcurrentHashMap

data class RunningServerInfo(
    val workspaceId: String,
    val workspaceName: String,
    val port: Int,
    val command: String,
    val mode: String, // "embedded-http" | "process"
    val startedAtMs: Long = System.currentTimeMillis(),
    val recentLogs: List<String> = emptyList()
) {
    val previewUrl: String get() = "http://127.0.0.1:$port/"
}

/**
 * Manages background local dev servers strictly bound to 127.0.0.1:<port>.
 * Runs either a real native process (if the requested binary exists in the sandbox)
 * or an embedded HTTP/1.1 static + SPA dev server serving the isolated workspace directory.
 */
class LocalDevServerManager(
    private val sandboxBootstrap: SandboxBootstrap,
    private val onServerCountChanged: (Int) -> Unit = {}
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val embeddedServers = ConcurrentHashMap<String, EmbeddedServerHandle>()
    private val processServers = ConcurrentHashMap<String, Process>()

    private val _runningServers = MutableStateFlow<Map<String, RunningServerInfo>>(emptyMap())
    val runningServers: StateFlow<Map<String, RunningServerInfo>> = _runningServers.asStateFlow()

    private class EmbeddedServerHandle(
        val socket: ServerSocket,
        val acceptJob: Job
    )

    fun getServerForWorkspace(workspaceId: String): RunningServerInfo? = _runningServers.value[workspaceId]

    fun startServer(
        workspaceId: String,
        workspaceName: String,
        workspaceDir: File,
        requestedCommand: String?,
        requestedPort: Int?
    ): RunningServerInfo {
        stopServer(workspaceId)

        val preferredPort = (requestedPort ?: 8080).coerceIn(1024, 65535)
        val cmd = requestedCommand?.trim().orEmpty().ifBlank { "torve-httpd --port $preferredPort" }

        // Check if command references a binary actually present on device (e.g. node, python3, busybox)
        val firstToken = cmd.split(Regex("\\s+")).firstOrNull().orEmpty()
        val hasExternalBinary = firstToken.isNotBlank() &&
            firstToken != "torve-httpd" &&
            isBinaryAvailable(firstToken)

        if (hasExternalBinary) {
            val startedProcess = runCatching {
                val pb = ProcessBuilder("/system/bin/sh", "-c", cmd)
                    .directory(workspaceDir)
                    .redirectErrorStream(true)
                pb.environment()["PORT"] = preferredPort.toString()
                pb.environment()["HOST"] = "127.0.0.1"
                pb.start()
            }.getOrNull()

            if (startedProcess != null && startedProcess.isAlive) {
                processServers[workspaceId] = startedProcess
                val info = RunningServerInfo(
                    workspaceId = workspaceId,
                    workspaceName = workspaceName,
                    port = preferredPort,
                    command = cmd,
                    mode = "process",
                    recentLogs = listOf("Started process server on http://127.0.0.1:$preferredPort")
                )
                putServerInfo(workspaceId, info)
                streamProcessLogs(workspaceId, startedProcess)
                return info
            }
        }

        // Launch reliable embedded loopback HTTP/1.1 dev server bound strictly to 127.0.0.1
        val serverSocket = bindLoopbackSocket(preferredPort)
        val actualPort = serverSocket.localPort
        val serveRoot = resolveWebRoot(workspaceDir)

        val acceptJob = scope.launch {
            while (isActive && !serverSocket.isClosed) {
                val clientSocket = try {
                    serverSocket.accept()
                } catch (_: Exception) {
                    break
                }
                launch {
                    handleHttpClient(workspaceId, serveRoot, clientSocket)
                }
            }
        }

        embeddedServers[workspaceId] = EmbeddedServerHandle(serverSocket, acceptJob)
        val info = RunningServerInfo(
            workspaceId = workspaceId,
            workspaceName = workspaceName,
            port = actualPort,
            command = cmd,
            mode = "embedded-http",
            recentLogs = listOf(
                "Serving ${serveRoot.name}/ on http://127.0.0.1:$actualPort",
                "Bound strictly to loopback 127.0.0.1"
            )
        )
        putServerInfo(workspaceId, info)
        return info
    }

    fun stopServer(workspaceId: String): Boolean {
        var stopped = false
        embeddedServers.remove(workspaceId)?.let { handle ->
            stopped = true
            runCatching { handle.socket.close() }
            handle.acceptJob.cancel()
        }
        processServers.remove(workspaceId)?.let { proc ->
            stopped = true
            runCatching { proc.destroyForcibly() }
        }
        val next = _runningServers.value.toMutableMap()
        if (next.remove(workspaceId) != null) {
            stopped = true
            _runningServers.value = next
            onServerCountChanged(next.size)
        }
        return stopped
    }

    fun stopAllServers() {
        val ids = _runningServers.value.keys.toList()
        ids.forEach { stopServer(it) }
    }

    private fun putServerInfo(workspaceId: String, info: RunningServerInfo) {
        val next = _runningServers.value.toMutableMap()
        next[workspaceId] = info
        _runningServers.value = next
        onServerCountChanged(next.size)
    }

    private fun appendLog(workspaceId: String, line: String) {
        val current = _runningServers.value[workspaceId] ?: return
        val updatedLogs = (current.recentLogs + line).takeLast(60)
        val next = _runningServers.value.toMutableMap()
        next[workspaceId] = current.copy(recentLogs = updatedLogs)
        _runningServers.value = next
    }

    private fun streamProcessLogs(workspaceId: String, process: Process) {
        scope.launch {
            runCatching {
                BufferedReader(InputStreamReader(process.inputStream)).use { reader ->
                    var line: String?
                    while (reader.readLine().also { line = it } != null) {
                        appendLog(workspaceId, line.orEmpty())
                    }
                }
            }
        }
    }

    private fun bindLoopbackSocket(preferredPort: Int): ServerSocket {
        val loopback = InetAddress.getByName("127.0.0.1")
        return try {
            ServerSocket(preferredPort, 32, loopback)
        } catch (_: Exception) {
            // Fall back to any free high loopback port if preferred port is busy
            ServerSocket(0, 32, loopback)
        }
    }

    private fun resolveWebRoot(workspaceDir: File): File {
        val candidates = listOf("dist", "build", "public")
        for (sub in candidates) {
            val dir = File(workspaceDir, sub)
            if (dir.isDirectory && File(dir, "index.html").exists()) {
                return dir
            }
        }
        return workspaceDir
    }

    private fun handleHttpClient(workspaceId: String, serveRoot: File, socket: Socket) {
        socket.use { client ->
            client.soTimeout = 5_000
            val reader = BufferedReader(InputStreamReader(client.getInputStream(), StandardCharsets.UTF_8))
            val requestLine = reader.readLine() ?: return
            val parts = requestLine.split(" ")
            if (parts.size < 2) return
            val method = parts[0].uppercase()
            val rawTarget = parts[1]

            // Drain HTTP headers
            while (true) {
                val h = reader.readLine() ?: break
                if (h.isEmpty()) break
            }

            val out = client.getOutputStream()
            if (method != "GET" && method != "HEAD") {
                writeTextResponse(out, 405, "Method Not Allowed", "text/plain", "Method Not Allowed")
                return
            }

            val cleanPath = URLDecoder.decode(rawTarget.substringBefore("?"), StandardCharsets.UTF_8.name())
            val relative = cleanPath.trimStart('/')

            val targetFile = try {
                val resolved = sandboxBootstrap.resolveJailedPath(serveRoot, relative)
                when {
                    resolved.isDirectory -> File(resolved, "index.html").takeIf { it.exists() }
                    resolved.exists() -> resolved
                    else -> null
                }
            } catch (_: SecurityException) {
                writeTextResponse(out, 403, "Forbidden", "text/plain", "403 Forbidden")
                appendLog(workspaceId, "403 $method $cleanPath")
                return
            }

            if (targetFile != null && targetFile.isFile) {
                val mime = detectMimeType(targetFile.name)
                val bytes = targetFile.readBytes()
                writeBinaryResponse(out, 200, "OK", mime, bytes, includeBody = method == "GET")
                appendLog(workspaceId, "200 $method $cleanPath (${bytes.size}B)")
            } else {
                // Directory listing fallback if index.html hasn't been created yet
                val rootFiles = serveRoot.listFiles()?.sortedBy { it.name }.orEmpty()
                val html = buildString {
                    append("<!doctype html><html><head><meta charset='utf-8'><meta name='viewport' content='width=device-width,initial-scale=1'>")
                    append("<title>Torve Workspace Server</title>")
                    append("<style>body{font-family:system-ui,sans-serif;background:#0B0F17;color:#F1F5F9;padding:24px;}a{color:#22D3EE;}</style></head><body>")
                    append("<h2>Torve Local Server</h2><p>Path <code>$cleanPath</code> not found. Workspace files:</p><ul>")
                    if (rootFiles.isEmpty()) {
                        append("<li>(empty workspace)</li>")
                    } else {
                        for (f in rootFiles) {
                            val suffix = if (f.isDirectory) "/" else ""
                            append("<li><a href=\"/${f.name}$suffix\">${f.name}$suffix</a></li>")
                        }
                    }
                    append("</ul></body></html>")
                }
                val status = if (relative.isEmpty()) 200 else 404
                val reason = if (status == 200) "OK" else "Not Found"
                writeTextResponse(out, status, reason, "text/html; charset=utf-8", html)
                appendLog(workspaceId, "$status $method $cleanPath")
            }
        }
    }

    private fun writeTextResponse(
        out: OutputStream,
        status: Int,
        reason: String,
        contentType: String,
        body: String
    ) {
        writeBinaryResponse(out, status, reason, contentType, body.toByteArray(StandardCharsets.UTF_8), true)
    }

    private fun writeBinaryResponse(
        out: OutputStream,
        status: Int,
        reason: String,
        contentType: String,
        body: ByteArray,
        includeBody: Boolean
    ) {
        val headers = buildString {
            append("HTTP/1.1 $status $reason\r\n")
            append("Content-Type: $contentType\r\n")
            append("Content-Length: ${body.size}\r\n")
            append("Cache-Control: no-store\r\n")
            append("Connection: close\r\n\r\n")
        }
        out.write(headers.toByteArray(StandardCharsets.US_ASCII))
        if (includeBody) {
            out.write(body)
        }
        out.flush()
    }

    private fun detectMimeType(fileName: String): String {
        return when (fileName.substringAfterLast('.', "").lowercase()) {
            "html", "htm" -> "text/html; charset=utf-8"
            "css" -> "text/css; charset=utf-8"
            "js", "mjs" -> "application/javascript; charset=utf-8"
            "json" -> "application/json; charset=utf-8"
            "svg" -> "image/svg+xml"
            "png" -> "image/png"
            "jpg", "jpeg" -> "image/jpeg"
            "webp" -> "image/webp"
            "ico" -> "image/x-icon"
            "txt", "md" -> "text/plain; charset=utf-8"
            "wasm" -> "application/wasm"
            else -> "application/octet-stream"
        }
    }

    private fun isBinaryAvailable(binary: String): Boolean {
        return runCatching {
            val proc = ProcessBuilder("/system/bin/sh", "-c", "command -v $binary").start()
            proc.waitFor() == 0
        }.getOrDefault(false)
    }
}
