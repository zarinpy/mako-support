package com.mako.support.inject

import com.intellij.openapi.util.TextRange

internal data class ControlBody(val keyword: String, val range: TextRange)

internal fun controlKeyword(text: String): String? {
    val percent = text.indexOf('%')
    if (percent < 0) return null
    var start = percent + 1
    while (start < text.length && (text[start] == ' ' || text[start] == '\t')) start++
    if (start >= text.length) return null
    val keyword = keywordAt(text, start)
    return keyword.ifEmpty { null }
}

internal fun controlBody(text: String): ControlBody? {
    val percent = text.indexOf('%')
    if (percent < 0) return null
    var start = percent + 1
    while (start < text.length && (text[start] == ' ' || text[start] == '\t')) start++
    if (start >= text.length) return null
    val keyword = keywordAt(text, start)
    if (keyword.isEmpty() || keyword.startsWith("end")) return null
    return ControlBody(keyword, TextRange(start, text.length))
}

internal fun compoundPrelude(keyword: String, indent: String): String = when (keyword) {
    "elif", "else" -> "${indent}if True:\n${indent}    pass\n"
    "except" -> "${indent}try:\n${indent}    pass\n"
    "finally" -> "${indent}try:\n${indent}    pass\n${indent}except Exception:\n${indent}    pass\n"
    else -> ""
}

internal fun compoundSuffix(keyword: String, indent: String): String = when (keyword) {
    "if", "elif", "else", "for", "while", "try", "except", "finally", "with", "def", "class", "async" ->
        "\n$indent    pass"
    else -> ""
}

private val BUILTIN_FILTERS = setOf("h", "u", "x", "trim", "entity", "str", "unicode", "n")

internal fun isBuiltinFilter(text: String): Boolean {
    val name = text.trim()
    if (name in BUILTIN_FILTERS) return true
    if (!name.startsWith("decode.")) return false
    val encoding = name.removePrefix("decode.")
    return encoding.isNotEmpty() && encoding.all { it.isLetterOrDigit() || it == '_' || it == '-' || it == '.' }
}

/** Comma-separated pieces, with ranges covering the trimmed text. */
internal fun splitFilters(text: String): List<TextRange> {
    return splitTopLevel(text, ',').mapNotNull { trimRange(text, it) }
}

/** Body of each `${...}` in [text], not including the delimiters. */
internal fun expressionHoles(text: String): List<Pair<TextRange, List<TextRange>>> {
    val holes = mutableListOf<Pair<TextRange, List<TextRange>>>()
    var index = 0
    while (index < text.length - 1) {
        if (text[index] == '$' && text[index + 1] == '{') {
            val close = closingBrace(text, index + 1)
            if (close < 0) break
            val innerStart = index + 2
            val inner = TextRange(innerStart, close)
            holes += expressionAndFilters(text, inner)
            index = close + 1
        } else {
            index++
        }
    }
    return holes
}

internal fun unquote(text: String): TextRange? {
    if (text.length < 2) return null
    val quote = text.first()
    if ((quote == '"' || quote == '\'') && text.last() == quote) {
        return TextRange(1, text.length - 1)
    }
    return TextRange(0, text.length)
}

internal data class Signature(val name: String, val parameters: String, val parameterNames: Set<String>)

internal fun parseSignature(value: String): Signature? {
    val text = value.trim()
    if (text.isEmpty()) return null
    val nameEnd = text.indexOfFirst { !(it.isLetterOrDigit() || it == '_') }
    if (nameEnd == 0) return null
    val name = if (nameEnd < 0) text else text.substring(0, nameEnd)
    if (!name.first().isLetter() && name.first() != '_') return null
    val parameters = if (nameEnd < 0) {
        ""
    } else {
        val rest = text.substring(nameEnd).trim()
        when {
            rest.startsWith("(") && rest.endsWith(")") -> rest.substring(1, rest.length - 1)
            rest.startsWith("(") -> rest.substring(1)
            else -> ""
        }
    }
    return Signature(name, parameters.trim(), parameterNames(parameters))
}

internal fun parameterNames(parameters: String): Set<String> {
    val names = linkedSetOf<String>()
    for (piece in splitTopLevel(stripStringsAndComments(parameters), ',')) {
        var part = piece.substring(parameters).trim()
        if (part.isEmpty() || part == "/" || part == "*") continue
        part = part.removePrefix("**").removePrefix("*").trim()
        val nameEnd = part.indexOfFirst { !(it.isLetterOrDigit() || it == '_') }
        val name = if (nameEnd < 0) part else part.substring(0, nameEnd)
        if (name.isNotEmpty() && (name.first().isLetter() || name.first() == '_') && name !in PYTHON_KEYWORDS) {
            names += name
        }
    }
    return names
}

internal fun declaredNames(code: String): Set<String> {
    val names = linkedSetOf<String>()
    val globals = linkedSetOf<String>()
    for (statement in statements(stripStringsAndComments(code))) {
        val trimmed = statement.trim()
        when {
            trimmed.startsWith("global ") || trimmed.startsWith("nonlocal ") -> {
                val rest = trimmed.substringAfter(' ')
                globals += targetNames(rest)
            }
            trimmed.startsWith("for ") -> {
                val header = trimmed.removePrefix("for ").substringBefore(':')
                val inAt = topLevelWord(header, "in")
                if (inAt >= 0) names += targetNames(header.substring(0, inAt))
            }
            trimmed.startsWith("def ") || trimmed.startsWith("class ") -> {
                names += keywordName(trimmed.substringAfter(' '))
            }
            trimmed.startsWith("async def ") || trimmed.startsWith("async class ") -> {
                names += keywordName(trimmed.substringAfter(' ').substringAfter(' '))
            }
            else -> {
                val eq = assignmentIndex(trimmed)
                if (eq >= 0) names += targetNames(trimmed.substring(0, eq))
            }
        }
    }
    names.removeAll(globals)
    names.removeAll(PYTHON_KEYWORDS)
    return names
}

internal fun lineRanges(text: String, range: TextRange): List<TextRange> {
    val slice = range.substring(text)
    val lines = mutableListOf<TextRange>()
    var start = 0
    while (start <= slice.length) {
        val newline = slice.indexOf('\n', start)
        if (newline < 0) {
            if (start < slice.length) lines += TextRange(range.startOffset + start, range.endOffset)
            break
        }
        if (newline > start) lines += TextRange(range.startOffset + start, range.startOffset + newline)
        start = newline + 1
        if (start == slice.length) break
    }
    return lines
}

private fun expressionAndFilters(text: String, inner: TextRange): Pair<TextRange, List<TextRange>> {
    val body = inner.substring(text)
    val bar = topLevelChar(body, '|')
    if (bar < 0) {
        val trimmed = trimRange(body, TextRange(0, body.length)) ?: TextRange(0, 0)
        return inner.startOffset.let { start -> trimmed.shiftRight(start) } to emptyList()
    }
    val expr = trimRange(body, TextRange(0, bar))
    val filters = splitFilters(body.substring(bar + 1)).map { it.shiftRight(inner.startOffset + bar + 1) }
    val expression = expr?.shiftRight(inner.startOffset) ?: TextRange(inner.startOffset, inner.startOffset)
    return expression to filters
}

private fun keywordAt(text: String, start: Int): String {
    var end = start
    while (end < text.length && (text[end].isLetterOrDigit() || text[end] == '_')) end++
    return text.substring(start, end)
}

private fun keywordName(afterKeyword: String): Set<String> {
    val trimmed = afterKeyword.trimStart()
    val name = keywordAt(trimmed, 0)
    return if (name.isEmpty() || name in PYTHON_KEYWORDS) emptySet() else setOf(name)
}

private fun targetNames(fragment: String): Set<String> {
    val names = mutableListOf<String>()
    var expectName = true
    var index = 0
    val text = fragment
    while (index < text.length) {
        val ch = text[index]
        when {
            ch == '(' || ch == '[' || ch == '{' -> {
                expectName = true
                index++
            }
            ch == ')' || ch == ']' || ch == '}' -> {
                expectName = false
                index++
            }
            ch == '.' -> {
                if (names.isNotEmpty()) names.removeAt(names.lastIndex)
                expectName = false
                index++
            }
            ch == ':' -> {
                expectName = false
                index++
            }
            ch == ',' -> {
                expectName = true
                index++
            }
            ch.isLetter() || ch == '_' -> {
                val start = index
                index++
                while (index < text.length && (text[index].isLetterOrDigit() || text[index] == '_')) index++
                val ident = text.substring(start, index)
                if (expectName && ident !in PYTHON_KEYWORDS) names += ident
                expectName = false
            }
            else -> index++
        }
    }
    return names.toSet()
}

private fun assignmentIndex(statement: String): Int {
    var depth = 0
    var index = 0
    while (index < statement.length) {
        val ch = statement[index]
        when (ch) {
            '(', '[', '{' -> depth++
            ')', ']', '}' -> if (depth > 0) depth--
            '=' -> if (depth == 0 && !isComparisonEquals(statement, index)) return index
        }
        index++
    }
    return -1
}

private fun isComparisonEquals(text: String, index: Int): Boolean {
    val prev = if (index > 0) text[index - 1] else '\u0000'
    val next = if (index + 1 < text.length) text[index + 1] else '\u0000'
    return next == '=' || prev == '=' || prev == '!' || prev == '<' || prev == '>'
}

private fun statements(stripped: String): List<String> {
    val parts = mutableListOf<String>()
    var depth = 0
    var start = 0
    var index = 0
    while (index < stripped.length) {
        val ch = stripped[index]
        when (ch) {
            '(', '[', '{' -> depth++
            ')', ']', '}' -> if (depth > 0) depth--
            '\n', ';' -> if (depth == 0) {
                parts += stripped.substring(start, index)
                start = index + 1
            }
        }
        index++
    }
    if (start < stripped.length) parts += stripped.substring(start)
    return parts
}

private fun splitTopLevel(text: String, separator: Char): List<TextRange> {
    val parts = mutableListOf<TextRange>()
    var depth = 0
    var start = 0
    var index = 0
    while (index < text.length) {
        when (val ch = text[index]) {
            '(', '[', '{' -> depth++
            ')', ']', '}' -> if (depth > 0) depth--
            separator -> if (depth == 0) {
                parts += TextRange(start, index)
                start = index + 1
            }
            else -> Unit
        }
        index++
    }
    parts += TextRange(start, text.length)
    return parts
}

private fun trimRange(text: String, range: TextRange): TextRange? {
    var start = range.startOffset
    var end = range.endOffset
    while (start < end && text[start].isWhitespace()) start++
    while (end > start && text[end - 1].isWhitespace()) end--
    if (start >= end) return null
    return TextRange(start, end)
}

private fun topLevelChar(text: String, marker: Char): Int {
    var depth = 0
    var index = 0
    while (index < text.length) {
        when (val ch = text[index]) {
            '(', '[', '{' -> depth++
            ')', ']', '}' -> if (depth > 0) depth--
            marker -> if (depth == 0) return index
        }
        index++
    }
    return -1
}

private fun topLevelWord(text: String, word: String): Int {
    var depth = 0
    var index = 0
    while (index < text.length) {
        val ch = text[index]
        when (ch) {
            '(', '[', '{' -> depth++
            ')', ']', '}' -> if (depth > 0) depth--
        }
        if (depth == 0 && text.startsWith(word, index) && isWordEdge(text, index, word.length)) {
            return index
        }
        index++
    }
    return -1
}

private fun isWordEdge(text: String, index: Int, length: Int): Boolean {
    val before = if (index > 0) text[index - 1] else ' '
    val afterIndex = index + length
    val after = if (afterIndex < text.length) text[afterIndex] else ' '
    return !before.isLetterOrDigit() && before != '_' && !after.isLetterOrDigit() && after != '_'
}

private fun closingBrace(text: String, openBrace: Int): Int {
    var depth = 0
    var index = openBrace
    while (index < text.length) {
        val ch = text[index]
        if (ch == '"' || ch == '\'') {
            val end = endOfString(text, index)
            if (end < 0) return -1
            index = end
            continue
        }
        when (ch) {
            '{' -> depth++
            '}' -> {
                depth--
                if (depth == 0) return index
            }
        }
        index++
    }
    return -1
}

private fun stripStringsAndComments(code: String): String {
    val out = StringBuilder(code.length)
    var index = 0
    while (index < code.length) {
        val ch = code[index]
        when {
            ch == '#' -> {
                while (index < code.length && code[index] != '\n') {
                    out.append(' ')
                    index++
                }
            }
            ch == '"' || ch == '\'' -> {
                val end = endOfString(code, index)
                val stop = if (end < 0) code.length else end
                while (index < stop) {
                    out.append(if (code[index] == '\n') '\n' else ' ')
                    index++
                }
            }
            else -> {
                out.append(ch)
                index++
            }
        }
    }
    return out.toString()
}

private fun endOfString(text: String, start: Int): Int {
    val quote = text[start]
    val triple = start + 2 < text.length && text[start + 1] == quote && text[start + 2] == quote
    var index = start + if (triple) 3 else 1
    while (index < text.length) {
        if (text[index] == '\\') {
            index += 2
            continue
        }
        if (triple) {
            if (index + 2 < text.length && text[index] == quote && text[index + 1] == quote && text[index + 2] == quote) {
                return index + 3
            }
        } else if (text[index] == quote) {
            return index + 1
        }
        index++
    }
    return -1
}

private val PYTHON_KEYWORDS = setOf(
    "False", "None", "True", "and", "as", "assert", "async", "await", "break", "class",
    "continue", "def", "del", "elif", "else", "except", "finally", "for", "from", "global",
    "if", "import", "in", "is", "lambda", "nonlocal", "not", "or", "pass", "raise", "return",
    "try", "while", "with", "yield",
)
