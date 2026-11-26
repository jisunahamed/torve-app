package com.example.feature.skills

import com.example.core.database.AppPreferencesStore
import com.example.feature.sandbox.SandboxBootstrap
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import java.io.File

/**
 * File-based Modular Agent Skills Engine.
 * Scans global skills (`$SANDBOX_ROOT/skills/<name>/SKILL.md`) and project-specific workspace
 * skills (`$WORKSPACE_DIR/.torve/skills/<name>/SKILL.md`), auto-installing 4 starter skills.
 */
class SkillRegistry(
    private val sandboxBootstrap: SandboxBootstrap,
    private val preferencesStore: AppPreferencesStore? = null
) {
    val globalSkillsRoot: File
        get() = File(sandboxBootstrap.sandboxRoot, "skills")

    private val _skills = MutableStateFlow<List<AgentSkill>>(emptyList())
    val skills: StateFlow<List<AgentSkill>> = _skills.asStateFlow()

    @Volatile
    private var disabledSkillNames: Set<String> = emptySet()

    fun ensureStarterSkillsInstalled() {
        sandboxBootstrap.sandboxRoot.mkdirs()
        globalSkillsRoot.mkdirs()

        STARTER_SKILLS.forEach { (slug, content) ->
            val dir = File(globalSkillsRoot, slug)
            val file = File(dir, SKILL_FILENAME)
            if (!file.exists()) {
                dir.mkdirs()
                file.writeText(content.trimIndent() + "\n")
            }
        }
        refreshSync()
    }

    suspend fun syncDisabledStateAndRefresh(workspaceDir: File? = null): List<AgentSkill> {
        disabledSkillNames = preferencesStore?.disabledSkillsFlow?.first() ?: emptySet()
        return scanSkills(workspaceDir)
    }

    fun refreshSync(workspaceDir: File? = null): List<AgentSkill> = scanSkills(workspaceDir)

    fun scanSkills(workspaceDir: File? = null): List<AgentSkill> {
        globalSkillsRoot.mkdirs()
        val byName = linkedMapOf<String, AgentSkill>()

        // 1. Scan global skills
        scanDirectory(globalSkillsRoot, SkillScope.GLOBAL).forEach { skill ->
            byName[skill.name] = skill
        }

        // 2. Scan workspace-local skills (.torve/skills/) — overrides global of same name
        if (workspaceDir != null && workspaceDir.exists()) {
            val wsSkillsDir = File(workspaceDir, ".torve/skills")
            if (wsSkillsDir.exists() && wsSkillsDir.isDirectory) {
                scanDirectory(wsSkillsDir, SkillScope.WORKSPACE).forEach { skill ->
                    byName[skill.name] = skill
                }
            }
        }

        val sorted = byName.values.sortedWith(
            compareByDescending<AgentSkill> { it.enabled }
                .thenByDescending { it.scope == SkillScope.WORKSPACE }
                .thenBy { it.name }
        )
        _skills.value = sorted
        return sorted
    }

    fun getEnabledSkills(workspaceDir: File? = null): List<AgentSkill> {
        return scanSkills(workspaceDir).filter { it.enabled }
    }

    fun loadSkillByName(rawName: String, workspaceDir: File? = null): AgentSkill? {
        val target = SkillFrontmatterParser.sanitizeSlug(rawName.removePrefix("/"))
        val all = scanSkills(workspaceDir)
        return all.firstOrNull { skill ->
            skill.enabled && (
                skill.name.equals(target, ignoreCase = true) ||
                    skill.triggers.any { it.removePrefix("/").equals(target, ignoreCase = true) }
                )
        }
    }

    /**
     * Parses an explicit slash-command invocation (e.g., `/web build a portfolio`).
     * Returns matched enabled AgentSkill + remaining user prompt, or null if no slash trigger matched.
     */
    fun matchSlashCommand(userPrompt: String, workspaceDir: File? = null): Pair<AgentSkill, String>? {
        val trimmed = userPrompt.trim()
        if (!trimmed.startsWith("/")) return null
        val firstToken = trimmed.substringBefore(" ").substringBefore("\n").trim()
        val remainder = trimmed.removePrefix(firstToken).trim()
        if (firstToken.length < 2) return null

        val enabled = getEnabledSkills(workspaceDir)
        val matched = enabled.firstOrNull { skill ->
            skill.triggers.any { it.equals(firstToken, ignoreCase = true) } ||
                "/${skill.name}".equals(firstToken, ignoreCase = true)
        } ?: return null

        return matched to remainder
    }

    /**
     * Compact catalog injected into the agent's system prompt for progressive disclosure via `load_skill`.
     */
    fun buildCatalogPromptSection(workspaceDir: File? = null): String {
        val enabled = getEnabledSkills(workspaceDir)
        if (enabled.isEmpty()) return ""
        return buildString {
            appendLine("Available modular skills (call load_skill(name) on demand to load full instructions when relevant):")
            enabled.forEach { skill ->
                val triggerStr = skill.primarySlashCommand
                val toolsHint = if (skill.allowedTools.isNotEmpty()) {
                    " [tools: ${skill.allowedTools.joinToString(", ")}]"
                } else ""
                appendLine("- ${skill.name} ($triggerStr): ${skill.description}$toolsHint")
            }
        }.trimEnd()
    }

    suspend fun setSkillEnabled(skillName: String, enabled: Boolean, workspaceDir: File? = null) {
        val slug = SkillFrontmatterParser.sanitizeSlug(skillName)
        disabledSkillNames = if (enabled) {
            disabledSkillNames - slug
        } else {
            disabledSkillNames + slug
        }
        preferencesStore?.setSkillEnabled(slug, enabled)
        scanSkills(workspaceDir)
    }

    fun saveCustomSkill(
        name: String,
        description: String,
        triggers: List<String>,
        allowedTools: List<String>,
        instructions: String,
        workspaceDir: File? = null
    ): AgentSkill {
        val slug = SkillFrontmatterParser.sanitizeSlug(name)
        val rootDir = if (workspaceDir != null) File(workspaceDir, ".torve/skills") else globalSkillsRoot
        rootDir.mkdirs()
        val skillDir = resolveConfinedSkillDir(rootDir, slug)
        skillDir.mkdirs()

        val markdown = SkillFrontmatterParser.serialize(
            name = slug,
            description = description,
            triggers = triggers,
            allowedTools = allowedTools,
            instructionsBody = instructions
        )
        val file = File(skillDir, SKILL_FILENAME)
        file.writeText(markdown)
        scanSkills(workspaceDir)
        return SkillFrontmatterParser.parse(
            rawMarkdown = markdown,
            fallbackName = slug,
            scope = if (workspaceDir != null) SkillScope.WORKSPACE else SkillScope.GLOBAL,
            filePath = file.absolutePath,
            enabled = slug !in disabledSkillNames,
            isBuiltIn = slug in STARTER_SKILLS.keys
        )
    }

    fun saveRawSkillMarkdown(skill: AgentSkill, newMarkdown: String, workspaceDir: File? = null): AgentSkill {
        val file = File(skill.filePath).canonicalFile
        val allowedRoots = listOfNotNull(
            globalSkillsRoot.canonicalFile,
            workspaceDir?.let { File(it, ".torve/skills").canonicalFile }
        )
        val isConfined = allowedRoots.any { root ->
            file.path.startsWith(root.path + File.separator)
        }
        if (!isConfined) {
            throw SecurityException("Refusing to write SKILL.md outside sandbox skills directory.")
        }
        file.parentFile?.mkdirs()
        file.writeText(newMarkdown)
        scanSkills(workspaceDir)
        return SkillFrontmatterParser.parse(
            rawMarkdown = newMarkdown,
            fallbackName = skill.name,
            scope = skill.scope,
            filePath = file.absolutePath,
            enabled = skill.enabled,
            isBuiltIn = skill.isBuiltIn
        )
    }

    fun deleteSkill(skill: AgentSkill, workspaceDir: File? = null): Boolean {
        if (skill.isBuiltIn && skill.scope == SkillScope.GLOBAL) return false
        val file = File(skill.filePath).canonicalFile
        val parent = file.parentFile ?: return false
        val allowedRoots = listOfNotNull(
            globalSkillsRoot.canonicalFile,
            workspaceDir?.let { File(it, ".torve/skills").canonicalFile }
        )
        val isConfined = allowedRoots.any { root ->
            parent.path.startsWith(root.path + File.separator)
        }
        if (!isConfined) return false
        val deleted = parent.deleteRecursively()
        scanSkills(workspaceDir)
        return deleted
    }

    private fun resolveConfinedSkillDir(skillsRoot: File, slug: String): File {
        val canonicalRoot = skillsRoot.canonicalFile
        val candidate = File(canonicalRoot, slug).canonicalFile
        if (!candidate.path.startsWith(canonicalRoot.path + File.separator)) {
            throw SecurityException("Invalid skill directory name: $slug")
        }
        return candidate
    }

    private fun scanDirectory(rootDir: File, scope: SkillScope): List<AgentSkill> {
        val children = rootDir.listFiles()?.filter { it.isDirectory }?.sortedBy { it.name } ?: return emptyList()
        val parsed = mutableListOf<AgentSkill>()
        for (dir in children) {
            val mdFile = File(dir, SKILL_FILENAME)
            if (!mdFile.exists() || !mdFile.isFile) continue
            val raw = runCatching { mdFile.readText() }.getOrNull() ?: continue
            val fallbackSlug = SkillFrontmatterParser.sanitizeSlug(dir.name)
            val skill = SkillFrontmatterParser.parse(
                rawMarkdown = raw,
                fallbackName = fallbackSlug,
                scope = scope,
                filePath = mdFile.absolutePath,
                enabled = fallbackSlug !in disabledSkillNames,
                isBuiltIn = fallbackSlug in STARTER_SKILLS.keys && scope == SkillScope.GLOBAL
            )
            val effectiveEnabled = skill.name !in disabledSkillNames
            parsed.add(skill.copy(enabled = effectiveEnabled))
        }
        return parsed
    }

    companion object {
        const val SKILL_FILENAME = "SKILL.md"

        val STARTER_SKILLS: Map<String, String> = linkedMapOf(
            "web-builder" to """
                ---
                name: web-builder
                description: Build responsive HTML/CSS/JS or Vite web apps and launch a live localhost preview.
                triggers: ["/web", "build a website", "landing page", "portfolio"]
                allowed_tools: ["write_file", "edit_file", "bash_exec", "manage_dev_server"]
                ---
                # Web Builder Instructions
                1. Inspect the active workspace with `list_files` if files may already exist.
                2. Scaffold a clean, responsive, modern web application (`index.html`, `styles.css`, `app.js`) using `write_file`.
                3. Ensure semantic HTML5, mobile-first responsive CSS with polished typography and dark/light visual hierarchy, and zero broken external dependencies.
                4. Always call `manage_dev_server(action="start", command="torve-httpd", port=8080)` after writing the files so the Live Preview pill activates immediately in the top bar.
                5. Summarize what was created and remind the user they can tap the green Live Preview button (`:8080`) in the header.
            """,
            "code-debugger" to """
                ---
                name: code-debugger
                description: Inspect files, run tests or checks via bash_exec, diagnose root causes, and apply minimal edit_file patches.
                triggers: ["/debug", "fix bug", "troubleshoot", "stack trace"]
                allowed_tools: ["list_files", "grep_search", "read_file", "bash_exec", "edit_file"]
                ---
                # Code Debugger Instructions
                1. Reproduce or inspect the issue first using `grep_search`, `read_file`, and `bash_exec`.
                2. State the exact root cause concisely with file paths and line numbers.
                3. Apply the smallest safe surgical fix using `edit_file` (avoid rewriting entire unrelated files).
                4. Re-verify the fix by reading the modified lines or re-running the failing check via `bash_exec`.
                5. Provide a brief summary of the root cause, patch applied, and verification outcome.
            """,
            "api-tester" to """
                ---
                name: api-tester
                description: Write and execute curl/shell/Python API test scripts in the sandbox and summarize status, headers, and latency.
                triggers: ["/api", "test endpoint", "curl", "http request"]
                allowed_tools: ["write_file", "bash_exec", "read_file"]
                ---
                # API Tester Instructions
                1. Clarify the target URL, HTTP method, headers, and payload from the user's request (never leak or hardcode real secret keys).
                2. Save a reusable test script inside the workspace (e.g., `scripts/test_api.sh`) using `write_file`.
                3. Execute the test script inside the sandbox via `bash_exec` capturing HTTP status code, response headers, timing (`time_total`), and formatted body preview.
                4. Present a crisp markdown table summarizing Endpoint, Status Code, Latency, Key Headers, and Response Body highlights.
            """,
            "project-explainer" to """
                ---
                name: project-explainer
                description: Map out workspace architecture, key files, entry points, and data flow using read-only exploration tools.
                triggers: ["/explain", "explain this project", "architecture", "codebase overview"]
                allowed_tools: ["list_files", "grep_search", "read_file"]
                ---
                # Project Explainer Instructions
                1. Start with `list_files(max_depth=3)` to map the workspace directory tree.
                2. Read key entry points, config files, and core modules using `read_file` and `grep_search`.
                3. Produce a clear, structured architectural overview covering:
                   - Purpose & Tech Stack
                   - Directory & Module Map
                   - Key Entry Points & Data Flow
                   - Suggested Next Steps or Potential Improvements
                4. Do not modify workspace files unless the user explicitly asks for changes.
            """
        )
    }
}
