package com.example

import com.example.core.database.MessageEntity
import com.example.core.database.ThreadEntity
import com.example.core.database.ToolCallLogEntity
import com.example.core.database.TorveDao
import com.example.core.database.WorkspaceEntity
import com.example.core.network.RateLimitQueue
import com.example.core.network.TorveApiClient
import com.example.core.network.TorveInferenceClient
import com.example.core.security.PkceManager
import com.example.feature.harness.HarnessToolRegistry
import com.example.feature.sandbox.LocalDevServerManager
import com.example.feature.sandbox.SandboxBootstrap
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
        val fakeDao = InMemoryTorveDao()
        val registry = HarnessToolRegistry(sandbox, serverManager, fakeDao, TorveApiClient.defaultTorveJson())

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
