package com.example.feature.sandbox

import android.content.Context
import android.os.Build
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.BufferedReader
import java.io.File
import java.io.InputStreamReader
import java.util.concurrent.TimeUnit

data class SandboxDiagnostics(
    val sandboxRootPath: String,
    val nativeLibDir: String,
    val shellBinary: String,
    val prootAvailable: Boolean,
    val busyboxAvailable: Boolean,
    val abi: String,
    val workspaceCount: Int,
    val isBootstrapped: Boolean
)

data class ShellExecResult(
    val exitCode: Int,
    val output: String,
    val timedOut: Boolean = false,
    val durationMs: Long = 0L
)

/**
 * Isolated non-root on-device Android Linux sandbox bootstrapper & workspace jail.
 * Respects Android 10+ W^X SELinux rules by detecting executable binaries in `nativeLibraryDir`
 * (e.g., `libproot.so`, `libbusybox.so`, `libbash.so`) and falling back cleanly to `/system/bin/sh`.
 */
class SandboxBootstrap(private val context: Context) {

    val sandboxRoot: File
        get() = File(context.filesDir, "sandbox")

    val workspacesRoot: File
        get() = File(sandboxRoot, "workspaces")

    val tmpDir: File
        get() = File(sandboxRoot, "tmp")

    val etcDir: File
        get() = File(sandboxRoot, "etc")

    private val nativeLibDir: File
        get() = File(context.applicationInfo.nativeLibraryDir ?: "")

    fun ensureBootstrapped(): SandboxDiagnostics {
        sandboxRoot.mkdirs()
        workspacesRoot.mkdirs()
        tmpDir.mkdirs()
        etcDir.mkdirs()

        val profileFile = File(etcDir, "profile")
        if (!profileFile.exists()) {
            profileFile.writeText(
                """
                # Torve AI On-Device Sandbox Environment
                export SANDBOX_ROOT="${sandboxRoot.absolutePath}"
                export TMPDIR="${tmpDir.absolutePath}"
                export TERM="xterm-256color"
                export LANG="C.UTF-8"
                """.trimIndent() + "\n"
            )
        }
        return getDiagnostics()
    }

    fun getDiagnostics(): SandboxDiagnostics {
        val prootBin = findNativeExecutable("libproot.so")
        val busyboxBin = findNativeExecutable("libbusybox.so")
        val bashBin = findNativeExecutable("libbash.so")
        val shell = bashBin?.absolutePath ?: "/system/bin/sh"
        val workspaceCount = workspacesRoot.listFiles()?.count { it.isDirectory } ?: 0
        return SandboxDiagnostics(
            sandboxRootPath = sandboxRoot.absolutePath,
            nativeLibDir = nativeLibDir.absolutePath,
            shellBinary = shell,
            prootAvailable = prootBin != null,
            busyboxAvailable = busyboxBin != null,
            abi = Build.SUPPORTED_ABIS.firstOrNull() ?: "arm64-v8a",
            workspaceCount = workspaceCount,
            isBootstrapped = sandboxRoot.exists() && workspacesRoot.exists()
        )
    }

    fun ensureWorkspaceDir(workspaceId: String): File {
        ensureBootstrapped()
        val safeId = workspaceId.replace(Regex("[^a-zA-Z0-9_-]"), "_")
        val dir = File(workspacesRoot, safeId)
        dir.mkdirs()
        return dir
    }

    fun deleteWorkspaceFiles(workspaceId: String) {
        val safeId = workspaceId.replace(Regex("[^a-zA-Z0-9_-]"), "_")
        val dir = File(workspacesRoot, safeId)
        if (dir.exists()) {
            dir.deleteRecursively()
        }
    }

    /**
     * Strict canonical path confinement: prevents path traversal outside the active workspace jail.
     */
    fun resolveJailedPath(workspaceDir: File, relativeOrSubPath: String?): File {
        val canonicalRoot = workspaceDir.canonicalFile
        val requested = relativeOrSubPath?.trim().orEmpty()
        val candidate = when {
            requested.isEmpty() || requested == "." || requested == "/" -> canonicalRoot
            requested.startsWith("/") -> {
                val stripped = requested.trimStart('/')
                if (File(requested).canonicalPath.startsWith(canonicalRoot.path)) {
                    File(requested).canonicalFile
                } else {
                    File(canonicalRoot, stripped).canonicalFile
                }
            }
            else -> File(canonicalRoot, requested).canonicalFile
        }
        val rootPath = canonicalRoot.path
        val candPath = candidate.path
        val isInside = candPath == rootPath || candPath.startsWith(rootPath + File.separator)
        if (!isInside) {
            throw SecurityException("Access denied: path '$relativeOrSubPath' escapes workspace jail.")
        }
        return candidate
    }

    /**
     * Executes a shell command inside the isolated workspace directory with streaming stdout/stderr
     * and output truncation to protect LLM context windows.
     */
    suspend fun executeShellCommand(
        workspaceDir: File,
        command: String,
        relativeCwd: String? = null,
        timeoutSec: Int = 30,
        maxOutputChars: Int = MAX_TOOL_OUTPUT_CHARS,
        onOutputChunk: ((String) -> Unit)? = null
    ): ShellExecResult = withContext(Dispatchers.IO) {
        val startMs = System.currentTimeMillis()
        ensureBootstrapped()
        val workingDir = resolveJailedPath(workspaceDir, relativeCwd)
        if (!workingDir.exists()) workingDir.mkdirs()

        val clampedTimeout = timeoutSec.coerceIn(2, 120)
        val shellBinary = findNativeExecutable("libbash.so")?.absolutePath ?: "/system/bin/sh"
        val processBuilder = ProcessBuilder(shellBinary, "-c", command)
            .directory(workingDir)
            .redirectErrorStream(true)

        val env = processBuilder.environment()
        env["HOME"] = workspaceDir.canonicalPath
        env["PWD"] = workingDir.canonicalPath
        env["WORKSPACE_DIR"] = workspaceDir.canonicalPath
        env["SANDBOX_ROOT"] = sandboxRoot.canonicalPath
        env["TMPDIR"] = tmpDir.canonicalPath
        env["TERM"] = "xterm-256color"
        val existingPath = env["PATH"] ?: "/system/bin:/system/xbin"
        val nativePath = nativeLibDir.takeIf { it.exists() }?.absolutePath
        env["PATH"] = listOfNotNull(nativePath, existingPath).joinToString(":")

        val process = try {
            processBuilder.start()
        } catch (e: Exception) {
            return@withContext ShellExecResult(
                exitCode = 127,
                output = "Failed to spawn sandbox shell: ${e.message}",
                durationMs = System.currentTimeMillis() - startMs
            )
        }

        val buffer = StringBuilder()
        var truncated = false
        val readerThread = Thread {
            try {
                BufferedReader(InputStreamReader(process.inputStream)).use { reader ->
                    var line: String?
                    while (reader.readLine().also { line = it } != null) {
                        val currentLine = line ?: break
                        if (buffer.length < maxOutputChars) {
                            buffer.append(currentLine).append('\n')
                            onOutputChunk?.invoke(buffer.toString())
                        } else if (!truncated) {
                            truncated = true
                            buffer.append("\n... [output truncated at $maxOutputChars chars] ...\n")
                            onOutputChunk?.invoke(buffer.toString())
                        }
                    }
                }
            } catch (_: Exception) {
                // Stream closed on timeout or cancel
            }
        }
        readerThread.start()

        try {
            val finished = process.waitFor(clampedTimeout.toLong(), TimeUnit.SECONDS)
            currentCoroutineContext().ensureActive()
            if (!finished) {
                process.destroyForcibly()
                readerThread.join(500)
                val out = buffer.toString().ifBlank { "(no output)" }
                return@withContext ShellExecResult(
                    exitCode = 124,
                    output = "$out\n[Command timed out after ${clampedTimeout}s]",
                    timedOut = true,
                    durationMs = System.currentTimeMillis() - startMs
                )
            }
            readerThread.join(1_000)
            val exitCode = process.exitValue()
            val finalOutput = buffer.toString().trimEnd().ifBlank { "(exit code $exitCode, no output)" }
            ShellExecResult(
                exitCode = exitCode,
                output = finalOutput,
                timedOut = false,
                durationMs = System.currentTimeMillis() - startMs
            )
        } catch (e: Exception) {
            process.destroyForcibly()
            throw e
        }
    }

    private fun findNativeExecutable(libName: String): File? {
        if (!nativeLibDir.exists()) return null
        val file = File(nativeLibDir, libName)
        return if (file.exists() && file.canExecute()) file else null
    }

    companion object {
        const val MAX_TOOL_OUTPUT_CHARS = 8_000
    }
}
