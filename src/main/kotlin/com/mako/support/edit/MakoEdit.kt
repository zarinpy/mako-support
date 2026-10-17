package com.mako.support.edit

import com.intellij.openapi.editor.Document
import com.intellij.openapi.util.TextRange
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import com.mako.support.inject.controlKeyword
import com.mako.support.lang.MakoLanguage

internal const val PLACEHOLDER_LIMIT = 40

internal val TAG_NAMES = listOf(
    "page", "include", "inherit", "namespace", "def", "block", "call", "text", "doc",
)

internal val BODY_TAGS = setOf("def", "block", "call", "text", "namespace", "doc")

internal val CACHE_ATTRIBUTES = listOf(
    "cache_key", "cache_timeout", "cache_type", "cache_dir", "cache_url", "cache_region",
)

internal val TAG_ATTRIBUTES: Map<String, List<String>> = mapOf(
    "page" to listOf("args", "expression_filter", "enable_loop", "cached") + CACHE_ATTRIBUTES,
    "include" to listOf("file", "import", "args"),
    "inherit" to listOf("file"),
    "namespace" to listOf("file", "name", "inheritable", "import", "module"),
    "text" to listOf("filter"),
    "def" to listOf("name", "filter", "decorator", "buffered", "cached") + CACHE_ATTRIBUTES,
    "block" to listOf("name", "filter", "decorator", "buffered", "cached", "args") + CACHE_ATTRIBUTES,
    "call" to listOf("expr", "args"),
)

internal val BOOLEAN_ATTRIBUTES = setOf("enable_loop", "cached", "buffered", "inheritable")

internal val CACHE_TYPES = listOf("memory", "file", "dbm", "ext:memcached", "memcached")

internal val FILTER_NAMES = listOf("h", "u", "x", "trim", "entity", "str", "unicode", "n", "decode.")

internal val CONTROL_KEYWORDS = listOf(
    "if", "for", "while", "try", "with", "elif", "else", "except", "finally",
)

internal val OPENERS = linkedSetOf("if", "for", "while", "try", "with")

internal fun makoPsi(file: PsiFile?): PsiFile? {
    if (file == null) return null
    if (file.language == MakoLanguage) return file
    return file.viewProvider.getPsi(MakoLanguage)
}

internal fun takesBody(keyword: String): Boolean {
    return keyword in BODY_TAGS || keyword.contains(':')
}

internal fun closerFor(keyword: String): String? = when (keyword) {
    "if" -> "endif"
    "for" -> "endfor"
    "while" -> "endwhile"
    "try" -> "endtry"
    "with" -> "endwith"
    else -> null
}

internal fun isKeywordChar(ch: Char): Boolean = ch.isLetterOrDigit() || ch == '_' || ch == '.' || ch == ':'

internal fun isControlLine(line: String): Boolean {
    val trimmed = line.trimStart()
    return trimmed.startsWith("%") && !trimmed.startsWith("%%")
}

internal fun lineStart(text: CharSequence, offset: Int): Int {
    val bound = offset.coerceIn(0, text.length)
    val newline = text.lastIndexOf('\n', (bound - 1).coerceAtLeast(0))
    return if (bound == 0 || newline < 0) 0 else newline + 1
}

internal fun lineEnd(text: CharSequence, offset: Int): Int {
    val newline = text.indexOf('\n', offset.coerceIn(0, text.length))
    return if (newline < 0) text.length else newline
}

internal fun indentColumn(whitespace: String, tabSize: Int): Int {
    var column = 0
    val size = tabSize.coerceAtLeast(1)
    for (ch in whitespace) {
        when (ch) {
            ' ' -> column++
            '\t' -> column += size - column % size
            else -> break
        }
    }
    return column
}

internal fun leadingWhitespace(line: String): String = line.takeWhile { it == ' ' || it == '\t' }

/** Keyword being typed after `%`, when the caret is still inside that word. */
internal fun controlPartial(text: CharSequence, caret: Int): TextRange? {
    val start = lineStart(text, caret)
    val line = text.subSequence(start, caret.coerceIn(start, text.length)).toString()
    val match = Regex("""^[ \t]*%[ \t]*([A-Za-z_][A-Za-z0-9_]*)?$""").find(line) ?: return null
    val partial = match.groupValues[1]
    return TextRange(caret - partial.length, caret)
}

internal fun tagPartial(text: CharSequence, caret: Int): TagPartial? {
    val bound = caret.coerceIn(0, text.length)
    var index = bound
    while (index > 0 && isKeywordChar(text[index - 1])) index--
    if (index < 2 || text[index - 2] != '<' || text[index - 1] != '%') return null
    val typed = text.subSequence(index, bound).toString()
    if (typed.any { it == '\n' || it == '>' || it.isWhitespace() }) return null
    val colon = typed.indexOf(':')
    return if (colon >= 0) {
        val namespace = typed.substring(0, colon)
        if (namespace.isEmpty()) return null
        TagPartial(namespace, typed.substring(colon + 1), index + colon + 1)
    } else {
        TagPartial(null, typed, index)
    }
}

internal data class TagPartial(val namespace: String?, val partial: String, val replaceStart: Int)

internal fun conditionText(control: String): String {
    val keyword = controlKeyword(control) ?: return ""
    val percent = control.indexOf('%')
    if (percent < 0) return ""
    var index = percent + 1
    while (index < control.length && control[index].isWhitespace()) index++
    index += keyword.length
    return control.substring(index).trim()
}

internal fun trimmedPlaceholder(text: String): String {
    val trimmed = text.trim()
    return if (trimmed.length <= PLACEHOLDER_LIMIT) trimmed else trimmed.substring(0, PLACEHOLDER_LIMIT)
}

internal fun elementAt(file: PsiFile, caret: Int): PsiElement? {
    if (file.textLength == 0) return null
    val offset = when {
        caret <= 0 -> 0
        caret >= file.textLength -> file.textLength - 1
        else -> caret - 1
    }
    return file.findElementAt(offset)
}

internal fun lineText(text: CharSequence, offset: Int): String {
    val start = lineStart(text, offset)
    return text.subSequence(start, lineEnd(text, start)).toString()
}

/**
 * Inserts the matching closer for an opener when it is absent, and leaves the
 * caret on the body line. A closer that is already there is left alone.
 * Returns the caret offset.
 */
internal fun openControlBlock(document: Document, openerOffset: Int, indentSize: Int): Int {
    val text = document.charsSequence
    val start = lineStart(text, openerOffset)
    var end = lineEnd(text, start)
    var line = text.subSequence(start, end).toString()
    val keyword = controlKeyword(line) ?: return openerOffset
    if (keyword !in OPENERS) return openerOffset
    val closerKeyword = closerFor(keyword) ?: return openerOffset
    if (!line.trimEnd().endsWith(':')) {
        document.insertString(end, ":")
        end += 1
        line = document.charsSequence.subSequence(start, end).toString()
    }
    val percent = line.indexOf('%')
    var cursor = percent + 1
    while (cursor < line.length && line[cursor].isWhitespace()) cursor++
    val spaced = cursor > percent + 1
    val openerIndent = leadingWhitespace(line)
    val bodyIndent = openerIndent + " ".repeat(indentSize.coerceAtLeast(0))
    val closerText = openerIndent + (if (spaced) "% " else "%") + closerKeyword
    val closerLine = findCloserLine(document.charsSequence, end, keyword, closerKeyword)
    val next = if (end < document.textLength && document.charsSequence[end] == '\n') end + 1 else -1
    if (closerLine == null) {
        document.insertString(end, "\n$bodyIndent\n$closerText")
        return end + 1 + bodyIndent.length
    }
    if (next < 0) {
        document.insertString(document.textLength, "\n$bodyIndent")
        return document.textLength
    }
    if (next == closerLine) {
        document.insertString(next, "$bodyIndent\n")
        return next + bodyIndent.length
    }
    val nextEnd = lineEnd(document.charsSequence, next)
    val nextLine = document.charsSequence.subSequence(next, nextEnd).toString()
    val whitespace = leadingWhitespace(nextLine)
    if (nextLine.isBlank()) {
        document.replaceString(next, next + whitespace.length, bodyIndent)
        return next + bodyIndent.length
    }
    return next + whitespace.length
}

private fun findCloserLine(text: CharSequence, openerLineEnd: Int, keyword: String, closerKeyword: String): Int? {
    var depth = 1
    var offset = if (openerLineEnd < text.length && text[openerLineEnd] == '\n') openerLineEnd + 1 else return null
    while (offset <= text.length) {
        val end = lineEnd(text, offset)
        val line = text.subSequence(offset, end).toString()
        when (controlKeyword(line)) {
            keyword -> depth++
            closerKeyword -> {
                depth--
                if (depth == 0) return offset
            }
        }
        if (end >= text.length) break
        offset = end + 1
    }
    return null
}
