package com.example.feature.chat

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.ui.theme.JetBrainsMonoFamily
import com.example.ui.theme.TorveCodeBgDark
import com.example.ui.theme.TorveCyan
import com.example.ui.theme.TorveEmerald
import com.example.ui.theme.TorveIndigoBright
import kotlinx.coroutines.delay

private sealed class MarkdownBlock {
    data class TextParagraph(val text: String) : MarkdownBlock()
    data class CodeBlock(val language: String, val code: String) : MarkdownBlock()
}

/**
 * Renders Markdown prose + syntax-highlighted fenced code blocks with 1-tap Copy.
 */
@Composable
fun MarkdownMessageContent(
    markdown: String,
    modifier: Modifier = Modifier
) {
    val blocks = remember(markdown) { parseMarkdownBlocks(markdown) }
    val baseColor = MaterialTheme.colorScheme.onSurface
    val inlineCodeBg = MaterialTheme.colorScheme.surfaceContainerHighest

    SelectionContainer(modifier = modifier) {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            blocks.forEach { block ->
                when (block) {
                    is MarkdownBlock.CodeBlock -> {
                        SyntaxHighlightedCodeCard(
                            language = block.language,
                            code = block.code
                        )
                    }
                    is MarkdownBlock.TextParagraph -> {
                        block.text.lines().forEach { rawLine ->
                            val line = rawLine.trimEnd()
                            when {
                                line.startsWith("### ") -> Text(
                                    text = formatInlineMarkdown(line.removePrefix("### "), inlineCodeBg),
                                    style = MaterialTheme.typography.titleMedium,
                                    color = baseColor
                                )
                                line.startsWith("## ") -> Text(
                                    text = formatInlineMarkdown(line.removePrefix("## "), inlineCodeBg),
                                    style = MaterialTheme.typography.titleLarge,
                                    color = baseColor
                                )
                                line.startsWith("# ") -> Text(
                                    text = formatInlineMarkdown(line.removePrefix("# "), inlineCodeBg),
                                    style = MaterialTheme.typography.headlineSmall,
                                    color = baseColor
                                )
                                line.startsWith("- ") || line.startsWith("* ") -> {
                                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                        Text("•", color = MaterialTheme.colorScheme.secondary, style = MaterialTheme.typography.bodyMedium)
                                        Text(
                                            text = formatInlineMarkdown(line.drop(2), inlineCodeBg),
                                            style = MaterialTheme.typography.bodyMedium,
                                            color = baseColor
                                        )
                                    }
                                }
                                line.isNotBlank() -> {
                                    Text(
                                        text = formatInlineMarkdown(line, inlineCodeBg),
                                        style = MaterialTheme.typography.bodyMedium,
                                        color = baseColor
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun SyntaxHighlightedCodeCard(
    language: String,
    code: String,
    modifier: Modifier = Modifier
) {
    val clipboard = LocalClipboardManager.current
    var copied by remember { mutableStateOf(false) }

    LaunchedEffect(copied) {
        if (copied) {
            delay(1800)
            copied = false
        }
    }

    val highlighted = remember(code, language) { highlightCode(code) }

    Surface(
        shape = RoundedCornerShape(14.dp),
        color = TorveCodeBgDark,
        border = BorderStroke(1.dp, Color(0xFF243047)),
        modifier = modifier.fillMaxWidth()
    ) {
        Column(modifier = Modifier.fillMaxWidth()) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(Color(0xFF131A29))
                    .padding(horizontal = 12.dp, vertical = 4.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = language.ifBlank { "code" }.lowercase(),
                    style = MaterialTheme.typography.labelSmall,
                    color = TorveCyan
                )
                TextButton(
                    onClick = {
                        clipboard.setText(AnnotatedString(code))
                        copied = true
                    }
                ) {
                    Icon(
                        imageVector = if (copied) Icons.Filled.Check else Icons.Filled.ContentCopy,
                        contentDescription = "Copy code",
                        tint = if (copied) TorveEmerald else Color(0xFF94A3B8),
                        modifier = Modifier.size(15.dp)
                    )
                    Spacer(Modifier.width(6.dp))
                    Text(
                        text = if (copied) "Copied" else "Copy",
                        style = MaterialTheme.typography.labelSmall,
                        color = if (copied) TorveEmerald else Color(0xFFCBD5E1)
                    )
                }
            }

            Text(
                text = highlighted,
                fontFamily = JetBrainsMonoFamily,
                fontSize = 12.5.sp,
                lineHeight = 18.sp,
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState())
                    .padding(12.dp)
            )
        }
    }
}

private fun parseMarkdownBlocks(input: String): List<MarkdownBlock> {
    val result = mutableListOf<MarkdownBlock>()
    val lines = input.lines()
    val textBuf = StringBuilder()
    var inFence = false
    var fenceLang = ""
    val codeBuf = StringBuilder()

    for (line in lines) {
        val trimmed = line.trimStart()
        if (trimmed.startsWith("```")) {
            if (!inFence) {
                if (textBuf.isNotBlank()) {
                    result.add(MarkdownBlock.TextParagraph(textBuf.toString().trimEnd()))
                    textBuf.clear()
                }
                inFence = true
                fenceLang = trimmed.removePrefix("```").trim()
                codeBuf.clear()
            } else {
                result.add(MarkdownBlock.CodeBlock(fenceLang, codeBuf.toString().trimEnd()))
                inFence = false
                fenceLang = ""
                codeBuf.clear()
            }
        } else {
            if (inFence) {
                codeBuf.append(line).append('\n')
            } else {
                textBuf.append(line).append('\n')
            }
        }
    }

    if (inFence && codeBuf.isNotEmpty()) {
        result.add(MarkdownBlock.CodeBlock(fenceLang, codeBuf.toString().trimEnd()))
    } else if (textBuf.isNotBlank()) {
        result.add(MarkdownBlock.TextParagraph(textBuf.toString().trimEnd()))
    }
    return result
}

private fun formatInlineMarkdown(line: String, inlineCodeBg: Color): AnnotatedString {
    return buildAnnotatedString {
        var i = 0
        while (i < line.length) {
            when {
                line.startsWith("**", i) -> {
                    val end = line.indexOf("**", i + 2)
                    if (end > i + 2) {
                        withStyle(SpanStyle(fontWeight = FontWeight.Bold)) {
                            append(line.substring(i + 2, end))
                        }
                        i = end + 2
                    } else {
                        append("**")
                        i += 2
                    }
                }
                line[i] == '`' -> {
                    val end = line.indexOf('`', i + 1)
                    if (end > i + 1) {
                        withStyle(
                            SpanStyle(
                                fontFamily = JetBrainsMonoFamily,
                                background = inlineCodeBg,
                                color = TorveCyan
                            )
                        ) {
                            append(" ${line.substring(i + 1, end)} ")
                        }
                        i = end + 1
                    } else {
                        append('`')
                        i++
                    }
                }
                else -> {
                    append(line[i])
                    i++
                }
            }
        }
    }
}

private val CODE_KEYWORDS = setOf(
    "fun", "val", "var", "class", "object", "interface", "return", "if", "else", "when", "for", "while",
    "import", "package", "const", "let", "function", "async", "await", "export", "default", "from",
    "def", "class", "try", "catch", "finally", "true", "false", "null", "public", "private", "override"
)

private fun highlightCode(code: String): AnnotatedString {
    return buildAnnotatedString {
        val defaultColor = Color(0xFFE2E8F0)
        val keywordColor = TorveIndigoBright
        val stringColor = TorveEmerald
        val commentColor = Color(0xFF64748B)
        val tagColor = TorveCyan

        code.lines().forEachIndexed { lineIdx, line ->
            if (lineIdx > 0) append('\n')
            val trimmed = line.trimStart()
            if (trimmed.startsWith("//") || trimmed.startsWith("#") || trimmed.startsWith("<!--")) {
                withStyle(SpanStyle(color = commentColor, fontFamily = FontFamily.Monospace)) {
                    append(line)
                }
            } else {
                var idx = 0
                while (idx < line.length) {
                    val ch = line[idx]
                    when {
                        ch == '"' || ch == '\'' -> {
                            val end = line.indexOf(ch, idx + 1)
                            if (end > idx) {
                                withStyle(SpanStyle(color = stringColor)) {
                                    append(line.substring(idx, end + 1))
                                }
                                idx = end + 1
                            } else {
                                withStyle(SpanStyle(color = defaultColor)) { append(ch) }
                                idx++
                            }
                        }
                        ch == '<' -> {
                            val end = line.indexOf('>', idx + 1)
                            if (end in (idx + 1)..(idx + 40)) {
                                withStyle(SpanStyle(color = tagColor)) {
                                    append(line.substring(idx, end + 1))
                                }
                                idx = end + 1
                            } else {
                                withStyle(SpanStyle(color = defaultColor)) { append(ch) }
                                idx++
                            }
                        }
                        ch.isLetter() || ch == '_' -> {
                            var end = idx + 1
                            while (end < line.length && (line[end].isLetterOrDigit() || line[end] == '_')) end++
                            val word = line.substring(idx, end)
                            val color = if (word in CODE_KEYWORDS) keywordColor else defaultColor
                            withStyle(SpanStyle(color = color)) { append(word) }
                            idx = end
                        }
                        else -> {
                            withStyle(SpanStyle(color = defaultColor)) { append(ch) }
                            idx++
                        }
                    }
                }
            }
        }
    }
}
