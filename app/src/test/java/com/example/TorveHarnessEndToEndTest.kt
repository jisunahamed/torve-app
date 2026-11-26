package com.example

import com.example.core.database.MessageEntity
import com.example.core.database.ThreadEntity
import com.example.core.database.ToolCallLogEntity
import com.example.core.database.TorveDao
import com.example.core.database.WorkspaceEntity
import com.example.core.network.ChatCompletionRequest
import com.example.core.network.ChatMessageDto
import com.example.core.network.RateLimitQueue
import com.example.core.network.TorveApiClient
import com.example.core.network.TorveInferenceClient
import com.example.core.security.PkceManager
import com.example.feature.harness.HarnessToolRegistry
import com.example.feature.harness.SseToolCallAccumulator
import com.example.feature.sandbox.LocalDevServerManager
import com.example.feature.sandbox.SandboxBootstrap
import com.example.feature.skills.SkillRegistry
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.nio.file.Files

class TorveHarnessEndToEndTest {

    @Test
    fun `pkce generator complies with RFC 7636 S256 and strict one-time state verification`() {
        val pkce = PkceManager()
        val session = pkce.startSession("Pixel 9 Pro")

        assertTrue(session.state.length in 16..512)
        assertTrue(session.codeVerifier.length in 43..128)
        assertEquals(PkceManager.computeS256Challenge(session.codeVerifier), session.codeChallenge)

        val url = pkce.buildAuthorizationUrl(session)
        assertTrue(url.startsWith("https://torveai.com/auth/desktop?"))
        assertTrue(url.contains("redirect_uri=torve%3A%2F%2Fauth%2Fcallback"))
        assertTrue(url.contains("code_challenge_method=S256"))
        assertTrue(url.contains("state=${session.state}"))

        // Wrong state must be rejected without consuming valid session
        assertNull(pkce.consumeIfStateMatches("invalid-state-value"))
        assertTrue(pkce.hasActiveSession())

        // Matching state succeeds once and atomically consumes the verifier
        val consumed = pkce.consumeIfStateMatches(session.state)
        assertNotNull(consumed)
        assertEquals(session.codeVerifier, consumed?.codeVerifier)
        assertFalse(pkce.hasActiveSession())
        assertNull(pkce.consumeIfStateMatches(session.state))
    }

    @Test
    fun `sse chunk parser decodes reasoning content tool calls and usage`() {
        val client = TorveInferenceClient(rateLimitQueue = RateLimitQueue())
        val raw = """
            {
              "id": "chatcmpl-123",
              "choices": [{
                "index": 0,
                "delta": {
                  "reasoning_content": "Planning index.html layout",
                  "content": "Building portfolio...",
                  "tool_calls": [{
                    "index": 0,
                    "id": "call_abc",
                    "function": {"name": "write_file", "arguments": "{\"path\":\"index.html\"}"}
                  }]
                }
              }],
              "usage": {"prompt_tokens": 120, "completion_tokens": 45, "total_tokens": 165}
            }
        """.trimIndent()

        val chunk = client.parseSseChunk(raw)
        assertNotNull(chunk)
        assertEquals("Planning index.html layout", chunk?.choices?.first()?.delta?.reasoningContent)
        assertEquals("Building portfolio...", chunk?.choices?.first()?.delta?.content)
        assertEquals("write_file", chunk?.choices?.first()?.delta?.toolCalls?.first()?.function?.name)
        assertEquals(165, chunk?.usage?.totalTokens)
    }

    @Test
    fun `workspace jail blocks path traversal attempts`() {
        val tempRoot = Files.createTempDirectory("torve_jail_test").toFile()
        try {
            val sandbox = createJvmSandboxBootstrap(tempRoot)
            val wsDir = File(tempRoot, "ws_security").apply { mkdirs() }

            var blocked = false
            try {
                sandbox.resolveJailedPath(wsDir, "../../etc/passwd")
            } catch (_: SecurityException) {
                blocked = true
            }
            assertTrue("Path traversal must be blocked", blocked)

            val valid = sandbox.resolveJailedPath(wsDir, "src/index.html")
            assertTrue(valid.canonicalPath.startsWith(wsDir.canonicalPath))
        } finally {
            tempRoot.deleteRecursively()
        }
    }

    @Test
    fun `end to end portfolio website creation and localhost server preview verification`() = runTest {
        val tempRoot = Files.createTempDirectory("torve_portfolio_e2e").toFile()
        val sandbox = createJvmSandboxBootstrap(tempRoot)
        val serverManager = LocalDevServerManager(sandbox)
        val skillRegistry = SkillRegistry(sandbox).apply { ensureStarterSkillsInstalled() }
        val fakeDao = InMemoryTorveDao()
        val registry = HarnessToolRegistry(
            sandboxBootstrap = sandbox,
            devServerManager = serverManager,
            torveDao = fakeDao,
            skillRegistry = skillRegistry,
            json = TorveApiClient.defaultTorveJson()
        )

        val wsId = "ws_portfolio_e2e"
        val wsDir = File(tempRoot, wsId).apply { mkdirs() }

        try {
            // 1. Write responsive HTML & CSS via agent write_file tool
            val writeHtml = registry.executeTool(
                workspaceId = wsId,
                workspaceName = "Portfolio Site",
                workspaceDir = wsDir,
                toolName = "write_file",
                argumentsJson = """{"path":"index.html","content":"<!doctype html><html><head><link rel='stylesheet' href='styles.css'></head><body><h1>Torve Portfolio</h1></body></html>"}"""
            )
            assertFalse("writeHtml failed: ${writeHtml.output}", writeHtml.isError)

            val writeCss = registry.executeTool(
                workspaceId = wsId,
                workspaceName = "Portfolio Site",
                workspaceDir = wsDir,
                toolName = "write_file",
                argumentsJson = """{"path":"styles.css","content":"body { background: #0B0F17; color: #F1F5F9; }"}"""
            )
            assertFalse("writeCss failed: ${writeCss.output}", writeCss.isError)

            // 2. Surgical edit via edit_file tool
            val editHtml = registry.executeTool(
                workspaceId = wsId,
                workspaceName = "Portfolio Site",
                workspaceDir = wsDir,
                toolName = "edit_file",
                argumentsJson = """{"path":"index.html","old_str":"Torve Portfolio","new_str":"Torve AI Engineer Portfolio"}"""
            )
            assertFalse("editHtml failed: ${editHtml.output}", editHtml.isError)
            assertTrue("editHtml diff missing", editHtml.diffContent?.contains("+ ") == true)

            // 3. Verify grep_search finds updated heading
            val grepRes = registry.executeTool(
                workspaceId = wsId,
                workspaceName = "Portfolio Site",
                workspaceDir = wsDir,
                toolName = "grep_search",
                argumentsJson = """{"pattern":"Torve AI Engineer Portfolio"}"""
            )
            assertFalse("grepRes failed: ${grepRes.output}", grepRes.isError)
            assertTrue("grepRes output: ${grepRes.output}", grepRes.output.contains("index.html"))

            // 4. Start local loopback dev server via manage_dev_server
            val startRes = registry.executeTool(
                workspaceId = wsId,
                workspaceName = "Portfolio Site",
                workspaceDir = wsDir,
                toolName = "manage_dev_server",
                argumentsJson = """{"action":"start","command":"torve-httpd","port":18480}"""
            )
            assertFalse("startRes failed: ${startRes.output}", startRes.isError)
            val port = startRes.startedServerPort
            assertNotNull("Server port was null", port)

            // 5. Real HTTP GET to 127.0.0.1:<port>/ verifying live serving
            val conn = URL("http://127.0.0.1:$port/").openConnection() as HttpURLConnection
            conn.connectTimeout = 3000
            conn.readTimeout = 3000
            assertEquals("HTML GET status", 200, conn.responseCode)
            val htmlBody = conn.inputStream.bufferedReader().readText()
            assertTrue("HTML body mismatch: $htmlBody", htmlBody.contains("Torve AI Engineer Portfolio"))
            conn.disconnect()

            val cssConn = URL("http://127.0.0.1:$port/styles.css").openConnection() as HttpURLConnection
            assertEquals("CSS GET status", 200, cssConn.responseCode)
            assertTrue("CSS contentType: ${cssConn.contentType}", cssConn.contentType.startsWith("text/css"))
            cssConn.disconnect()

            // 6. Stop server cleanly
            val stopRes = registry.executeTool(
                workspaceId = wsId,
                workspaceName = "Portfolio Site",
                workspaceDir = wsDir,
                toolName = "manage_dev_server",
                argumentsJson = """{"action":"stop"}"""
            )
            assertFalse("stopRes failed: ${stopRes.output}", stopRes.isError)
            assertNull("Server should be stopped", serverManager.getServerForWorkspace(wsId))
        } finally {
            serverManager.stopAllServers()
            tempRoot.deleteRecursively()
        }
    }

    @Test
    fun `sse tool call accumulator assembles multiple interleaved tool deltas by index`() {
        val accumulator = SseToolCallAccumulator()

        // First chunk introduces index 0 and index 1 headers
        accumulator.appendDelta(index = 0, id = "call_1", nameChunk = "write_file", argumentsChunk = "{\"path\":\"a.txt\"")
        accumulator.appendDelta(index = 1, id = "call_2", nameChunk = "read_file", argumentsChunk = "{\"path\":")

        // Second chunk streams remaining argument fragments interleaved
        accumulator.appendDelta(index = 0, id = null, nameChunk = null, argumentsChunk = ",\"content\":\"hello\"}")
        accumulator.appendDelta(index = 1, id = null, nameChunk = null, argumentsChunk = "\"a.txt\"}")

        val calls = accumulator.buildFinalizedToolCalls(stepNumber = 1)
        assertEquals(2, calls.size)
        assertEquals("call_1", calls[0].id)
        assertEquals("write_file", calls[0].function.name)
        assertEquals("""{"path":"a.txt","content":"hello"}""", calls[0].function.arguments)
        assertEquals("call_2", calls[1].id)
        assertEquals("read_file", calls[1].function.name)
        assertEquals("""{"path":"a.txt"}""", calls[1].function.arguments)
    }

    @Test
    fun `openai tools payload attaches tools and tool_choice auto when supported and omits cleanly in chat only mode`() {
        val tempRoot = Files.createTempDirectory("torve_payload_test").toFile()
        val sandbox = createJvmSandboxBootstrap(tempRoot)
        val serverManager = LocalDevServerManager(sandbox)
        val skillRegistry = SkillRegistry(sandbox)
        val json = TorveApiClient.defaultTorveJson()
        val registry = HarnessToolRegistry(sandbox, serverManager, InMemoryTorveDao(), skillRegistry, json)

        try {
            val tools = registry.getToolDefinitions()
            val toolNames = tools.map { it.function.name }.toSet()
            assertEquals(
                setOf("bash_exec", "read_file", "write_file", "edit_file", "list_files", "grep_search", "manage_dev_server", "load_skill"),
                toolNames
            )

            val withToolsJson = json.encodeToString(
                ChatCompletionRequest.serializer(),
                ChatCompletionRequest(
                    model = "torve-pro",
                    messages = listOf(ChatMessageDto(role = "user", content = "Hi")),
                    maxTokens = 4096,
                    tools = tools,
                    toolChoice = "auto"
                )
            )
            assertTrue(withToolsJson.contains("\"tools\""))
            assertTrue(withToolsJson.contains("\"tool_choice\":\"auto\""))
            assertTrue(withToolsJson.contains("\"type\":\"function\""))
            assertTrue(withToolsJson.contains("\"load_skill\""))

            val chatOnlyJson = json.encodeToString(
                ChatCompletionRequest.serializer(),
                ChatCompletionRequest(
                    model = "torve-chat-only",
                    messages = listOf(ChatMessageDto(role = "user", content = "Hi")),
                    maxTokens = 4096,
                    tools = null,
                    toolChoice = null
                )
            )
            assertFalse("Chat-only request must omit tools field", chatOnlyJson.contains("\"tools\""))
            assertFalse("Chat-only request must omit tool_choice field", chatOnlyJson.contains("\"tool_choice\""))
        } finally {
            serverManager.stopAllServers()
            tempRoot.deleteRecursively()
        }
    }

    @Test
    fun `malformed tool json arguments and unknown tools return structured error json without crashing`() = runTest {
        val tempRoot = Files.createTempDirectory("torve_error_recovery_test").toFile()
        val sandbox = createJvmSandboxBootstrap(tempRoot)
        val serverManager = LocalDevServerManager(sandbox)
        val skillRegistry = SkillRegistry(sandbox).apply { ensureStarterSkillsInstalled() }
        val registry = HarnessToolRegistry(
            sandboxBootstrap = sandbox,
            devServerManager = serverManager,
            torveDao = InMemoryTorveDao(),
            skillRegistry = skillRegistry,
            json = TorveApiClient.defaultTorveJson()
        )
        val wsDir = File(tempRoot, "ws_err").apply { mkdirs() }

        try {
            val malformed = registry.executeTool(
                workspaceId = "ws_err",
                workspaceName = "Error Test",
                workspaceDir = wsDir,
                toolName = "edit_file",
                argumentsJson = """{"path": "index.html", "old_str": UNTERMINATED"""
            )
            assertTrue("Expected error flag for malformed JSON", malformed.isError)
            assertTrue("Expected structured JSON error: ${malformed.output}", malformed.output.contains("\"error\""))
            assertTrue("Expected edit_file mentioned: ${malformed.output}", malformed.output.contains("Invalid JSON arguments for edit_file"))

            val unknown = registry.executeTool(
                workspaceId = "ws_err",
                workspaceName = "Error Test",
                workspaceDir = wsDir,
                toolName = "non_existent_tool",
                argumentsJson = """{}"""
            )
            assertTrue("Expected error flag for unknown tool", unknown.isError)
            assertTrue("Expected structured JSON error: ${unknown.output}", unknown.output.contains("Unknown tool"))
        } finally {
            serverManager.stopAllServers()
            tempRoot.deleteRecursively()
        }
    }

    @Test
    fun `modular skills engine installs starter skills matches slash commands supports custom crud and load_skill tool`() = runTest {
        val tempRoot = Files.createTempDirectory("torve_skills_test").toFile()
        val sandbox = createJvmSandboxBootstrap(tempRoot)
        val serverManager = LocalDevServerManager(sandbox)
        val skillRegistry = SkillRegistry(sandbox).apply { ensureStarterSkillsInstalled() }
        val registry = HarnessToolRegistry(
            sandboxBootstrap = sandbox,
            devServerManager = serverManager,
            torveDao = InMemoryTorveDao(),
            skillRegistry = skillRegistry,
            json = TorveApiClient.defaultTorveJson()
        )
        val wsDir = File(tempRoot, "ws_skills").apply { mkdirs() }

        try {
            // 1. All 4 starter skills exist and are enabled
            val installed = skillRegistry.scanSkills(wsDir)
            val names = installed.map { it.name }.toSet()
            assertTrue(names.containsAll(setOf("web-builder", "code-debugger", "api-tester", "project-explainer")))

            // 2. Slash command matching
            val match = skillRegistry.matchSlashCommand("/web create a landing page", wsDir)
            assertNotNull("Expected /web to match web-builder", match)
            assertEquals("web-builder", match?.first?.name)
            assertEquals("create a landing page", match?.second)

            // 3. load_skill harness tool returns SKILL.md body & loadedSkillName
            val loadRes = registry.executeTool(
                workspaceId = "ws_skills",
                workspaceName = "Skills Test",
                workspaceDir = wsDir,
                toolName = "load_skill",
                argumentsJson = """{"name":"web-builder"}"""
            )
            assertFalse("load_skill failed: ${loadRes.output}", loadRes.isError)
            assertEquals("web-builder", loadRes.loadedSkillName)
            assertTrue(loadRes.output.contains("manage_dev_server"))

            // 4. Custom skill creation, toggle off, and deletion
            val created = skillRegistry.saveCustomSkill(
                name = "sql-reviewer",
                description = "Audit SQL migrations for safety.",
                triggers = listOf("/sql", "review sql"),
                allowedTools = listOf("read_file", "grep_search"),
                instructions = "# SQL Reviewer\n1. Check indexes and constraints."
            )
            assertEquals("sql-reviewer", created.name)
            assertNotNull(skillRegistry.matchSlashCommand("/sql check schema.sql", wsDir))

            skillRegistry.setSkillEnabled("sql-reviewer", false, wsDir)
            assertNull("Disabled skill should not match slash command", skillRegistry.matchSlashCommand("/sql check", wsDir))

            assertTrue("Custom skill should delete cleanly", skillRegistry.deleteSkill(created, wsDir))
            assertFalse(skillRegistry.scanSkills(wsDir).any { it.name == "sql-reviewer" })
        } finally {
            serverManager.stopAllServers()
            tempRoot.deleteRecursively()
        }
    }

    private fun createJvmSandboxBootstrap(rootDir: File): SandboxBootstrap {
        val fakeContext = object : android.content.ContextWrapper(null) {
            override fun getFilesDir(): File = rootDir
        }
        return SandboxBootstrap(fakeContext)
    }

    private class InMemoryTorveDao : TorveDao {
        override fun observeWorkspaces(): Flow<List<WorkspaceEntity>> = flowOf(emptyList())
        override suspend fun getWorkspaceById(workspaceId: String): WorkspaceEntity? = null
        override fun observeWorkspace(workspaceId: String): Flow<WorkspaceEntity?> = flowOf(null)
        override suspend fun upsertWorkspace(workspace: WorkspaceEntity) = Unit
        override suspend fun updateWorkspaceServer(workspaceId: String, port: Int?, command: String?, now: Long) = Unit
        override suspend fun updateWorkspaceProjectType(workspaceId: String, projectType: String, now: Long) = Unit
        override suspend fun deleteWorkspace(workspaceId: String) = Unit
        override fun observeActiveThreads(): Flow<List<ThreadEntity>> = flowOf(emptyList())
        override fun observeThread(threadId: String): Flow<ThreadEntity?> = flowOf(null)
        override suspend fun getThreadById(threadId: String): ThreadEntity? = null
        override suspend fun upsertThread(thread: ThreadEntity) = Unit
        override suspend fun updateThread(thread: ThreadEntity) = Unit
        override suspend fun setThreadStarred(threadId: String, starred: Boolean) = Unit
        override suspend fun setThreadArchived(threadId: String, archived: Boolean) = Unit
        override suspend fun updateThreadModel(threadId: String, modelId: String, now: Long) = Unit
        override suspend fun updateThreadTitle(threadId: String, title: String, now: Long) = Unit
        override suspend fun updateThreadActivity(threadId: String, snippet: String, addedTokens: Long, now: Long) = Unit
        override suspend fun deleteThread(threadId: String) = Unit
        override fun observeMessages(threadId: String): Flow<List<MessageEntity>> = flowOf(emptyList())
        override suspend fun getMessagesSnapshot(threadId: String): List<MessageEntity> = emptyList()
        override suspend fun upsertMessage(message: MessageEntity) = Unit
        override suspend fun updateMessageContent(
            messageId: String,
            content: String,
            reasoning: String?,
            toolCallsJson: String?,
            promptTokens: Int,
            completionTokens: Int,
            isStreaming: Boolean
        ) = Unit
        override fun observeToolCallLogs(threadId: String): Flow<List<ToolCallLogEntity>> = flowOf(emptyList())
        override suspend fun upsertToolCallLog(log: ToolCallLogEntity) = Unit
        override suspend fun updateToolCallResult(
            logId: String,
            output: String,
            diffContent: String?,
            status: String,
            durationMs: Long
        ) = Unit
    }
}
