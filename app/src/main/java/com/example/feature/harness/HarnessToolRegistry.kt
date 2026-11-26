package com.example.feature.harness

import com.example.core.database.TorveDao
import com.example.core.network.ToolDefinitionDto
import com.example.core.network.ToolFunctionSchemaDto
import com.example.feature.sandbox.LocalDevServerManager
import com.example.feature.sandbox.SandboxBootstrap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import java.io.File
import java.util.regex.Pattern

data class ToolExecutionResult(
    val output: String,
    val summary: String,
    val diffContent: String? = null,
    val isError: Boolean = false,
    val durationMs: Long = 0L,
    val startedServerPort: Int? = null
)

/**
 * Deterministic 7-tool harness registry exposed to Torve AI models via OpenAI Function Calling.
 * Enforces strict workspace jail confinement and 8,000-char output truncation.
 */
class HarnessToolRegistry(
    private val sandboxBootstrap: SandboxBootstrap,
    private val devServerManager: LocalDevServerManager,
    private val torveDao: TorveDao,
    private val json: Json
) {
    fun getToolDefinitions(): List<ToolDefinitionDto> = listOf(
        ToolDefinitionDto(
            function = ToolFunctionSchemaDto(
                name = "bash_exec",
                description = "Execute a shell command inside the isolated workspace sandbox. Use for listing, building, testing, or running scripts.",
                parameters = buildJsonObject {
                    put("type", "object")
                    put("properties", buildJsonObject {
                        put("command", buildJsonObject {
                            put("type", "string")
                            put("description", "POSIX shell command to execute")
                        })
                        put("cwd", buildJsonObject {
                            put("type", "string")
                            put("description", "Optional relative directory inside workspace")
                        })
                        put("timeout_sec", buildJsonObject {
                            put("type", "integer")
                            put("description", "Timeout in seconds (default 30, max 120)")
                        })
                    })
                    put("required", buildJsonArray { add(JsonPrimitive("command")) })
                }
            )
        ),
        ToolDefinitionDto(
            function = ToolFunctionSchemaDto(
                name = "read_file",
                description = "Read a file inside the workspace with line numbers.",
                parameters = buildJsonObject {
                    put("type", "object")
                    put("properties", buildJsonObject {
                        put("path", buildJsonObject {
                            put("type", "string")
                            put("description", "Relative file path in workspace")
                        })
                        put("start_line", buildJsonObject {
                            put("type", "integer")
                            put("description", "Optional 1-based starting line number")
                        })
                        put("end_line", buildJsonObject {
                            put("type", "integer")
                            put("description", "Optional 1-based ending line number")
                        })
                    })
                    put("required", buildJsonArray { add(JsonPrimitive("path")) })
                }
            )
        ),
        ToolDefinitionDto(
            function = ToolFunctionSchemaDto(
                name = "write_file",
                description = "Create or overwrite a file inside the workspace. Automatically creates parent directories.",
                parameters = buildJsonObject {
                    put("type", "object")
                    put("properties", buildJsonObject {
                        put("path", buildJsonObject {
                            put("type", "string")
                            put("description", "Relative file path in workspace")
                        })
                        put("content", buildJsonObject {
                            put("type", "string")
                            put("description", "Full text content to write")
                        })
                    })
                    put("required", buildJsonArray {
                        add(JsonPrimitive("path"))
                        add(JsonPrimitive("content"))
                    })
                }
            )
        ),
        ToolDefinitionDto(
            function = ToolFunctionSchemaDto(
                name = "edit_file",
                description = "Perform a surgical search-and-replace edit on an existing file inside the workspace.",
                parameters = buildJsonObject {
                    put("type", "object")
                    put("properties", buildJsonObject {
                        put("path", buildJsonObject {
                            put("type", "string")
                            put("description", "Relative file path in workspace")
                        })
                        put("old_str", buildJsonObject {
                            put("type", "string")
                            put("description", "Exact existing text substring to replace")
                        })
                        put("new_str", buildJsonObject {
                            put("type", "string")
                            put("description", "Replacement text")
                        })
                    })
                    put("required", buildJsonArray {
                        add(JsonPrimitive("path"))
                        add(JsonPrimitive("old_str"))
                        add(JsonPrimitive("new_str"))
                    })
                }
            )
        ),
        ToolDefinitionDto(
            function = ToolFunctionSchemaDto(
                name = "list_files",
                description = "List directory structure inside the workspace.",
                parameters = buildJsonObject {
                    put("type", "object")
                    put("properties", buildJsonObject {
                        put("path", buildJsonObject {
                            put("type", "string")
                            put("description", "Relative path inside workspace (default '.')")
                        })
                        put("max_depth", buildJsonObject {
                            put("type", "integer")
                            put("description", "Max traversal depth (default 3, max 6)")
                        })
                    })
                }
            )
        ),
        ToolDefinitionDto(
            function = ToolFunctionSchemaDto(
                name = "grep_search",
                description = "Search text or regex pattern across files inside the workspace.",
                parameters = buildJsonObject {
                    put("type", "object")
                    put("properties", buildJsonObject {
                        put("pattern", buildJsonObject {
                            put("type", "string")
                            put("description", "Search substring or regular expression")
                        })
                        put("path", buildJsonObject {
                            put("type", "string")
                            put("description", "Optional relative file or subfolder to search")
                        })
                    })
                    put("required", buildJsonArray { add(JsonPrimitive("pattern")) })
                }
            )
        ),
        ToolDefinitionDto(
            function = ToolFunctionSchemaDto(
                name = "manage_dev_server",
                description = "Start, stop, or inspect a local HTTP development server bound to 127.0.0.1:<port> for live in-app preview.",
                parameters = buildJsonObject {
                    put("type", "object")
                    put("properties", buildJsonObject {
                        put("action", buildJsonObject {
                            put("type", "string")
                            put("enum", buildJsonArray {
                                add(JsonPrimitive("start"))
                                add(JsonPrimitive("stop"))
                                add(JsonPrimitive("status"))
                            })
                            put("description", "Server lifecycle action")
                        })
                        put("command", buildJsonObject {
                            put("type", "string")
                            put("description", "Command to serve project (e.g. 'torve-httpd' or 'python3 -m http.server 8080')")
                        })
                        put("port", buildJsonObject {
                            put("type", "integer")
                            put("description", "Loopback port (default 8080)")
                        })
                    })
                    put("required", buildJsonArray { add(JsonPrimitive("action")) })
                }
            )
        )
    )

    fun summarizeArguments(toolName: String, argumentsJson: String): String {
        val args = parseArgs(argumentsJson)
        return when (toolName) {
            "bash_exec" -> args["command"]?.jsonPrimitive?.content?.take(64) ?: "shell command"
            "read_file" -> args["path"]?.jsonPrimitive?.content ?: "file"
            "write_file" -> args["path"]?.jsonPrimitive?.content ?: "file"
            "edit_file" -> args["path"]?.jsonPrimitive?.content ?: "file"
            "list_files" -> args["path"]?.jsonPrimitive?.content?.ifBlank { "." } ?: "."
            "grep_search" -> "/${args["pattern"]?.jsonPrimitive?.content.orEmpty()}/"
            "manage_dev_server" -> {
                val action = args["action"]?.jsonPrimitive?.content ?: "status"
                val port = args["port"]?.jsonPrimitive?.intOrNull
                if (port != null) "$action :$port" else action
            }
            else -> toolName
        }
    }

    suspend fun executeTool(
        workspaceId: String,
        workspaceName: String,
        workspaceDir: File,
        toolName: String,
        argumentsJson: String,
        onLiveOutput: ((String) -> Unit)? = null
    ): ToolExecutionResult = withContext(Dispatchers.IO) {
        val startMs = System.currentTimeMillis()
        val args = parseArgs(argumentsJson)
        val summary = summarizeArguments(toolName, argumentsJson)

        try {
            when (toolName) {
                "bash_exec" -> {
                    val command = args["command"]?.jsonPrimitive?.content
                        ?: return@withContext errorResult("Missing required argument 'command'", summary, startMs)
                    val cwd = args["cwd"]?.jsonPrimitive?.content
                    val timeoutSec = args["timeout_sec"]?.jsonPrimitive?.intOrNull ?: 30
                    val res = sandboxBootstrap.executeShellCommand(
                        workspaceDir = workspaceDir,
                        command = command,
                        relativeCwd = cwd,
                        timeoutSec = timeoutSec,
                        onOutputChunk = onLiveOutput
                    )
                    ToolExecutionResult(
                        output = truncateOutput("Exit code: ${res.exitCode}\n${res.output}"),
                        summary = summary,
                        isError = res.exitCode != 0,
                        durationMs = res.durationMs
                    )
                }

                "read_file" -> {
                    val path = args["path"]?.jsonPrimitive?.content
                        ?: return@withContext errorResult("Missing required argument 'path'", summary, startMs)
                    val file = sandboxBootstrap.resolveJailedPath(workspaceDir, path)
                    if (!file.exists() || !file.isFile) {
                        return@withContext errorResult("File not found: $path", summary, startMs)
                    }
                    val lines = file.readLines()
                    val startLine = (args["start_line"]?.jsonPrimitive?.intOrNull ?: 1).coerceAtLeast(1)
                    val endLine = (args["end_line"]?.jsonPrimitive?.intOrNull ?: lines.size).coerceAtMost(lines.size)
                    val formatted = buildString {
                        append("File: $path (${lines.size} lines)\n")
                        for (idx in startLine..endLine) {
                            append(String.format("%4d | %s\n", idx, lines[idx - 1]))
                        }
                    }
                    ToolExecutionResult(
                        output = truncateOutput(formatted),
                        summary = summary,
                        durationMs = System.currentTimeMillis() - startMs
                    )
                }

                "write_file" -> {
                    val path = args["path"]?.jsonPrimitive?.content
                        ?: return@withContext errorResult("Missing required argument 'path'", summary, startMs)
                    val content = args["content"]?.jsonPrimitive?.content
                        ?: return@withContext errorResult("Missing required argument 'content'", summary, startMs)
                    val file = sandboxBootstrap.resolveJailedPath(workspaceDir, path)
                    val existed = file.exists()
                    val oldContent = if (existed && file.isFile) file.readText() else ""
                    file.parentFile?.mkdirs()
                    file.writeText(content)

                    if (path.endsWith(".html", ignoreCase = true) || path.endsWith(".css", ignoreCase = true)) {
                        torveDao.updateWorkspaceProjectType(workspaceId, "web")
                    }

                    val diff = buildSimpleDiff(path, oldContent, content)
                    val lineCount = content.lines().size
                    ToolExecutionResult(
                        output = "${if (existed) "Updated" else "Created"} $path ($lineCount lines, ${content.length} chars)",
                        summary = summary,
                        diffContent = diff,
                        durationMs = System.currentTimeMillis() - startMs
                    )
                }

                "edit_file" -> {
                    val path = args["path"]?.jsonPrimitive?.content
                        ?: return@withContext errorResult("Missing required argument 'path'", summary, startMs)
                    val oldStr = args["old_str"]?.jsonPrimitive?.content
                        ?: return@withContext errorResult("Missing required argument 'old_str'", summary, startMs)
                    val newStr = args["new_str"]?.jsonPrimitive?.content
                        ?: return@withContext errorResult("Missing required argument 'new_str'", summary, startMs)
                    val file = sandboxBootstrap.resolveJailedPath(workspaceDir, path)
                    if (!file.exists() || !file.isFile) {
                        return@withContext errorResult("File not found: $path", summary, startMs)
                    }
                    val current = file.readText()
                    if (!current.contains(oldStr)) {
                        return@withContext errorResult(
                            "Target text not found in $path. Read the file first to confirm exact content.",
                            summary,
                            startMs
                        )
                    }
                    val updated = current.replaceFirst(oldStr, newStr)
                    file.writeText(updated)
                    val diff = buildTargetedDiff(path, oldStr, newStr)
                    ToolExecutionResult(
                        output = "Applied surgical edit to $path",
                        summary = summary,
                        diffContent = diff,
                        durationMs = System.currentTimeMillis() - startMs
                    )
                }

                "list_files" -> {
                    val path = args["path"]?.jsonPrimitive?.content
                    val maxDepth = (args["max_depth"]?.jsonPrimitive?.intOrNull ?: 3).coerceIn(1, 6)
                    val root = sandboxBootstrap.resolveJailedPath(workspaceDir, path)
                    if (!root.exists()) {
                        return@withContext errorResult("Directory not found: ${path ?: "."}", summary, startMs)
                    }
                    val tree = buildDirectoryTree(root, maxDepth)
                    ToolExecutionResult(
                        output = truncateOutput(tree.ifBlank { "(empty directory)" }),
                        summary = summary,
                        durationMs = System.currentTimeMillis() - startMs
                    )
                }

                "grep_search" -> {
                    val patternStr = args["pattern"]?.jsonPrimitive?.content
                        ?: return@withContext errorResult("Missing required argument 'pattern'", summary, startMs)
                    val path = args["path"]?.jsonPrimitive?.content
                    val root = sandboxBootstrap.resolveJailedPath(workspaceDir, path)
                    val regex = runCatching { Pattern.compile(patternStr, Pattern.CASE_INSENSITIVE) }.getOrNull()
                    val matches = mutableListOf<String>()
                    searchFiles(workspaceDir.canonicalFile, root, patternStr, regex, matches, maxMatches = 120)
                    val output = if (matches.isEmpty()) {
                        "No matches found for '$patternStr'."
                    } else {
                        matches.joinToString("\n")
                    }
                    ToolExecutionResult(
                        output = truncateOutput(output),
                        summary = summary,
                        durationMs = System.currentTimeMillis() - startMs
                    )
                }

                "manage_dev_server" -> {
                    val action = args["action"]?.jsonPrimitive?.content?.lowercase() ?: "status"
                    val command = args["command"]?.jsonPrimitive?.content
                    val port = args["port"]?.jsonPrimitive?.intOrNull ?: 8080
                    when (action) {
                        "start" -> {
                            val info = devServerManager.startServer(
                                workspaceId = workspaceId,
                                workspaceName = workspaceName,
                                workspaceDir = workspaceDir,
                                requestedCommand = command,
                                requestedPort = port
                            )
                            torveDao.updateWorkspaceServer(workspaceId, info.port, info.command)
                            torveDao.updateWorkspaceProjectType(workspaceId, "web")
                            ToolExecutionResult(
                                output = "Dev server running (${info.mode}) at ${info.previewUrl}\nLive Preview button is now active in the app top bar.",
                                summary = "start :${info.port}",
                                durationMs = System.currentTimeMillis() - startMs,
                                startedServerPort = info.port
                            )
                        }
                        "stop" -> {
                            val stopped = devServerManager.stopServer(workspaceId)
                            torveDao.updateWorkspaceServer(workspaceId, null, null)
                            ToolExecutionResult(
                                output = if (stopped) "Dev server stopped." else "No running dev server for this workspace.",
                                summary = "stop",
                                durationMs = System.currentTimeMillis() - startMs
                            )
                        }
                        else -> {
                            val active = devServerManager.getServerForWorkspace(workspaceId)
                            val msg = if (active != null) {
                                "Running (${active.mode}) at ${active.previewUrl}\nCommand: ${active.command}\nRecent logs:\n${active.recentLogs.takeLast(8).joinToString("\n")}"
                            } else {
                                "No dev server currently running for this workspace."
                            }
                            ToolExecutionResult(
                                output = msg,
                                summary = "status",
                                durationMs = System.currentTimeMillis() - startMs
                            )
                        }
                    }
                }

                else -> errorResult("Unknown tool: $toolName", summary, startMs)
            }
        } catch (se: SecurityException) {
            errorResult(se.message ?: "Workspace jail violation", summary, startMs)
        } catch (e: Exception) {
            errorResult("Tool error: ${e.localizedMessage ?: e::class.simpleName}", summary, startMs)
        }
    }

    fun buildWorkspaceTreeSnapshot(workspaceDir: File): String {
        return buildDirectoryTree(workspaceDir, maxDepth = 3).ifBlank { "(empty workspace)" }
    }

    private fun parseArgs(argumentsJson: String): JsonObject {
        if (argumentsJson.isBlank()) return JsonObject(emptyMap())
        return runCatching { json.parseToJsonElement(argumentsJson).jsonObject }
            .getOrDefault(JsonObject(emptyMap()))
    }

    private fun errorResult(message: String, summary: String, startMs: Long) = ToolExecutionResult(
        output = message,
        summary = summary,
        isError = true,
        durationMs = System.currentTimeMillis() - startMs
    )

    private fun truncateOutput(text: String): String {
        val max = SandboxBootstrap.MAX_TOOL_OUTPUT_CHARS
        return if (text.length <= max) text else text.take(max) + "\n... [truncated at $max chars]"
    }

    private fun buildDirectoryTree(root: File, maxDepth: Int): String {
        val sb = StringBuilder()
        fun walk(dir: File, depth: Int, indent: String) {
            if (depth > maxDepth) return
            val children = dir.listFiles()?.sortedWith(compareBy<File> { !it.isDirectory }.thenBy { it.name }) ?: return
            for (child in children.take(80)) {
                if (child.name == ".git" || child.name == "node_modules") {
                    sb.append("$indent${child.name}/ (skipped)\n")
                    continue
                }
                if (child.isDirectory) {
                    sb.append("$indent${child.name}/\n")
                    walk(child, depth + 1, "$indent  ")
                } else {
                    sb.append("$indent${child.name} (${child.length()}B)\n")
                }
            }
        }
        walk(root, 0, "")
        return sb.toString().trimEnd()
    }

    private fun searchFiles(
        workspaceRoot: File,
        current: File,
        literal: String,
        regex: Pattern?,
        out: MutableList<String>,
        maxMatches: Int
    ) {
        if (out.size >= maxMatches) return
        if (current.isDirectory) {
            if (current.name == ".git" || current.name == "node_modules") return
            current.listFiles()?.sortedBy { it.name }?.forEach {
                searchFiles(workspaceRoot, it, literal, regex, out, maxMatches)
            }
        } else if (current.isFile && current.length() <= 512_000L) {
            val relPath = current.relativeTo(workspaceRoot).path
            runCatching {
                current.readLines().forEachIndexed { index, line ->
                    if (out.size >= maxMatches) return
                    val matched = if (regex != null) regex.matcher(line).find() else line.contains(literal, ignoreCase = true)
                    if (matched) {
                        out.add("$relPath:${index + 1}: ${line.trim().take(160)}")
                    }
                }
            }
        }
    }

    private fun buildSimpleDiff(path: String, oldContent: String, newContent: String): String {
        val sb = StringBuilder("--- a/$path\n+++ b/$path\n")
        if (oldContent.isEmpty()) {
            newContent.lines().take(60).forEach { sb.append("+ ").append(it).append('\n') }
            if (newContent.lines().size > 60) sb.append("... (+${newContent.lines().size - 60} more lines)\n")
        } else {
            val oldLines = oldContent.lines()
            val newLines = newContent.lines()
            oldLines.take(20).forEach { if (!newLines.contains(it)) sb.append("- ").append(it).append('\n') }
            newLines.take(40).forEach { if (!oldLines.contains(it)) sb.append("+ ").append(it).append('\n') }
        }
        return sb.toString().trimEnd()
    }

    private fun buildTargetedDiff(path: String, oldStr: String, newStr: String): String {
        val sb = StringBuilder("--- a/$path\n+++ b/$path\n")
        oldStr.lines().forEach { sb.append("- ").append(it).append('\n') }
        newStr.lines().forEach { sb.append("+ ").append(it).append('\n') }
        return sb.toString().trimEnd()
    }
}
