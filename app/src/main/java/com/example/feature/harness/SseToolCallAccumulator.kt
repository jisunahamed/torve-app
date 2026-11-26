package com.example.feature.harness

import com.example.core.network.ToolCallDto
import com.example.core.network.ToolCallFunctionDto

/**
 * Incrementally assembles streamed OpenAI `delta.tool_calls` chunks by index.
 * Supports multiple concurrent tool calls arriving interleaved in a single assistant turn.
 */
class SseToolCallAccumulator {
    private class Entry {
        var id: String = ""
        val name = StringBuilder()
        val arguments = StringBuilder()
    }

    private val entries = linkedMapOf<Int, Entry>()

    fun appendDelta(
        index: Int,
        id: String?,
        nameChunk: String?,
        argumentsChunk: String?
    ) {
        val entry = entries.getOrPut(index) { Entry() }
        if (!id.isNullOrBlank() && entry.id.isBlank()) {
            entry.id = id.trim()
        }
        if (!nameChunk.isNullOrEmpty()) {
            val currentName = entry.name.toString()
            if (currentName.isEmpty()) {
                entry.name.append(nameChunk)
            } else if (nameChunk != currentName) {
                // Some providers stream function.name in chunks; ignore duplicate full-name echoes
                entry.name.append(nameChunk)
            }
        }
        if (!argumentsChunk.isNullOrEmpty()) {
            entry.arguments.append(argumentsChunk)
        }
    }

    fun isEmpty(): Boolean = entries.isEmpty()

    fun buildFinalizedToolCalls(stepNumber: Int): List<ToolCallDto> {
        return entries.entries
            .sortedBy { it.key }
            .mapNotNull { (idx, entry) ->
                val fnName = entry.name.toString().trim()
                if (fnName.isEmpty()) return@mapNotNull null
                val rawArgs = entry.arguments.toString()
                ToolCallDto(
                    id = entry.id.ifBlank { "call_${stepNumber}_$idx" },
                    type = "function",
                    function = ToolCallFunctionDto(
                        name = fnName,
                        arguments = rawArgs.ifBlank { "{}" }
                    )
                )
            }
    }
}
