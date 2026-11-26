package com.example.feature.skills

enum class SkillScope {
    GLOBAL,
    WORKSPACE
}

data class AgentSkill(
    val name: String,
    val description: String,
    val triggers: List<String>,
    val allowedTools: List<String>,
    val instructionsBody: String,
    val rawMarkdown: String,
    val scope: SkillScope,
    val filePath: String,
    val enabled: Boolean = true,
    val isBuiltIn: Boolean = false
) {
    val primarySlashCommand: String
        get() = triggers.firstOrNull { it.startsWith("/") } ?: "/$name"
}

/**
 * Parses and serializes SKILL.md files with YAML frontmatter + Markdown instructions body.
 */
object SkillFrontmatterParser {

    fun parse(
        rawMarkdown: String,
        fallbackName: String,
        scope: SkillScope,
        filePath: String,
        enabled: Boolean,
        isBuiltIn: Boolean
    ): AgentSkill {
        val normalized = rawMarkdown.replace("\r\n", "\n")
        val lines = normalized.lines()

        var name = fallbackName
        var description = ""
        val triggers = mutableListOf<String>()
        val allowedTools = mutableListOf<String>()
        var bodyStartIndex = 0

        if (lines.firstOrNull()?.trim() == "---") {
            var endIdx = -1
            for (i in 1 until lines.size) {
                if (lines[i].trim() == "---") {
                    endIdx = i
                    break
                }
            }
            if (endIdx > 0) {
                val frontmatterLines = lines.subList(1, endIdx)
                var currentListKey: String? = null
                for (line in frontmatterLines) {
                    val trimmed = line.trim()
                    if (trimmed.isEmpty() || trimmed.startsWith("#")) continue

                    if (trimmed.startsWith("- ") && currentListKey != null) {
                        val item = unquote(trimmed.removePrefix("- ").trim())
                        if (item.isNotBlank()) {
                            when (currentListKey) {
                                "triggers" -> triggers.add(item)
                                "allowed_tools" -> allowedTools.add(item)
                            }
                        }
                        continue
                    }

                    val colonIdx = trimmed.indexOf(':')
                    if (colonIdx <= 0) continue
                    val key = trimmed.substring(0, colonIdx).trim().lowercase()
                    val value = trimmed.substring(colonIdx + 1).trim()
                    currentListKey = null

                    when (key) {
                        "name" -> if (value.isNotBlank()) name = sanitizeSlug(unquote(value))
                        "description" -> description = unquote(value)
                        "triggers" -> {
                            if (value.isBlank()) {
                                currentListKey = "triggers"
                            } else {
                                triggers.addAll(parseInlineYamlArray(value))
                            }
                        }
                        "allowed_tools" -> {
                            if (value.isBlank()) {
                                currentListKey = "allowed_tools"
                            } else {
                                allowedTools.addAll(parseInlineYamlArray(value))
                            }
                        }
                    }
                }
                bodyStartIndex = endIdx + 1
            }
        }

        val body = if (bodyStartIndex < lines.size) {
            lines.subList(bodyStartIndex, lines.size).joinToString("\n").trim()
        } else {
            normalized.trim()
        }

        return AgentSkill(
            name = sanitizeSlug(name.ifBlank { fallbackName }),
            description = description.ifBlank { "Custom agent skill ($name)" },
            triggers = triggers.ifEmpty { listOf("/$name") },
            allowedTools = allowedTools,
            instructionsBody = body,
            rawMarkdown = normalized,
            scope = scope,
            filePath = filePath,
            enabled = enabled,
            isBuiltIn = isBuiltIn
        )
    }

    fun serialize(
        name: String,
        description: String,
        triggers: List<String>,
        allowedTools: List<String>,
        instructionsBody: String
    ): String {
        val safeName = sanitizeSlug(name)
        val cleanTriggers = triggers.map { it.trim() }.filter { it.isNotBlank() }.ifEmpty { listOf("/$safeName") }
        val cleanTools = allowedTools.map { it.trim() }.filter { it.isNotBlank() }

        return buildString {
            appendLine("---")
            appendLine("name: $safeName")
            appendLine("description: ${description.replace("\n", " ").trim()}")
            appendLine("triggers: [${cleanTriggers.joinToString(", ") { "\"${escapeQuotes(it)}\"" }}]")
            appendLine("allowed_tools: [${cleanTools.joinToString(", ") { "\"${escapeQuotes(it)}\"" }}]")
            appendLine("---")
            appendLine()
            append(instructionsBody.trim())
            appendLine()
        }
    }

    fun sanitizeSlug(raw: String): String {
        val cleaned = raw.trim().lowercase()
            .replace(Regex("[^a-z0-9_-]+"), "-")
            .trim('-', '_')
            .take(48)
        return cleaned.ifBlank { "custom-skill" }
    }

    private fun parseInlineYamlArray(raw: String): List<String> {
        val trimmed = raw.trim()
        if (trimmed.startsWith("[") && trimmed.endsWith("]")) {
            val inner = trimmed.substring(1, trimmed.length - 1)
            return inner.split(",")
                .map { unquote(it.trim()) }
                .filter { it.isNotBlank() }
        }
        return listOf(unquote(trimmed)).filter { it.isNotBlank() }
    }

    private fun unquote(value: String): String {
        val t = value.trim()
        return if ((t.startsWith("\"") && t.endsWith("\"")) || (t.startsWith("'") && t.endsWith("'"))) {
            t.substring(1, t.length - 1)
        } else {
            t
        }
    }

    private fun escapeQuotes(value: String): String = value.replace("\"", "\\\"")
}
