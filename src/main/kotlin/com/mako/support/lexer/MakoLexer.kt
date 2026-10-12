package com.mako.support.lexer

import com.intellij.lexer.LexerBase
import com.intellij.psi.TokenType
import com.intellij.psi.tree.IElementType

/**
 * Mako 1.4.3 structure lexer.
 *
 * Template text keeps the source characters: `$$` is text, and `%%` at the start of a line stays
 * in the document as text. A trailing `\` is its own token so the following newline remains text.
 * `<%text>` bodies stay text. `<%doc>` bodies do not.
 */
class MakoLexer : LexerBase() {
    private var buffer: CharSequence = ""
    private var endOffset: Int = 0
    private var position: Int = 0
    private var tokenStart: Int = 0
    private var tokenEnd: Int = 0
    private var tokenType: IElementType? = null
    private var state: Int = MODE_TEXT
    private var pendingState: Int = MODE_TEXT

    override fun start(buffer: CharSequence, startOffset: Int, endOffset: Int, initialState: Int) {
        this.buffer = buffer
        this.endOffset = endOffset
        position = startOffset
        pendingState = initialState
        tokenType = null
        tokenStart = startOffset
        tokenEnd = startOffset
        advance()
    }

    override fun getState(): Int = state

    override fun getTokenType(): IElementType? = tokenType

    override fun getTokenStart(): Int = tokenStart

    override fun getTokenEnd(): Int = tokenEnd

    override fun getBufferSequence(): CharSequence = buffer

    override fun getBufferEnd(): Int = endOffset

    override fun advance() {
        if (position >= endOffset) {
            state = pendingState
            tokenType = null
            tokenStart = endOffset
            tokenEnd = endOffset
            return
        }
        state = pendingState
        tokenStart = position
        tokenType = null
        tokenEnd = position
        when (mode(state)) {
            MODE_TEXT -> lexText()
            MODE_EXPR -> lexExpression()
            MODE_FILTER -> lexFilter()
            MODE_PY -> lexPython()
            MODE_MODULE_MARK -> lexModuleMark()
            MODE_RAW_TEXT -> lexRaw(closer = "</%text>", body = MakoTokenTypes.TEXT, closingIsTag = true)
            MODE_RAW_DOC -> lexRaw(closer = "</%doc>", body = MakoTokenTypes.DOC_BODY, closingIsTag = false)
            MODE_TAG -> lexTag()
            else -> emit(TokenType.BAD_CHARACTER, position + 1, MODE_TEXT)
        }
        if (tokenType != null && tokenEnd <= tokenStart) {
            tokenType = TokenType.BAD_CHARACTER
            tokenEnd = (tokenStart + 1).coerceAtMost(endOffset)
            pendingState = MODE_TEXT
        }
        position = if (tokenType == null) endOffset else tokenEnd
    }

    private fun lexText() {
        if (position == 0) {
            val cookieEnd = codingCookieEnd()
            if (cookieEnd > 0) {
                emit(MakoTokenTypes.CODING_COMMENT, cookieEnd, MODE_TEXT)
                return
            }
        }
        if (atLineStart(position)) {
            when (lineKind(position)) {
                LineKind.COMMENT -> {
                    emit(MakoTokenTypes.COMMENT, logicalLineEnd(position), MODE_TEXT)
                    return
                }
                LineKind.CONTROL -> {
                    emit(MakoTokenTypes.CONTROL, logicalLineEnd(position), MODE_TEXT)
                    return
                }
                null -> Unit
            }
        }
        var index = position
        while (index < endOffset) {
            if (index > position && atLineStart(index) && lineKind(index) != null) break
            if (startsWith(index, "</%") || startsWith(index, "<%") || startsWith(index, "\${")) break
            if (buffer[index] == '\\' && index + 1 < endOffset && isLineBreak(index + 1)) break
            index++
        }
        if (index > position) {
            emit(MakoTokenTypes.TEXT, index, MODE_TEXT)
            return
        }
        when {
            startsWith(position, "\${") -> emit(MakoTokenTypes.EXPR_START, position + 2, MODE_EXPR)
            startsWith(position, "</%") -> emit(MakoTokenTypes.TAG_CLOSE_OPEN, position + 3, tagState(PHASE_NAME, closing = true, textTag = false))
            startsWith(position, "<%") -> openAngle()
            buffer[position] == '\\' -> emit(MakoTokenTypes.NEWLINE_ESCAPE, position + 1, MODE_TEXT)
            else -> emit(MakoTokenTypes.TEXT, position + 1, MODE_TEXT)
        }
    }

    private fun openAngle() {
        if (position + 2 < endOffset && buffer[position + 2] == '!') {
            emit(MakoTokenTypes.PY_START, position + 2, MODE_MODULE_MARK)
            return
        }
        if (startsWith(position, "<%doc>")) {
            emit(MakoTokenTypes.DOC_START, position + "<%doc>".length, MODE_RAW_DOC)
            return
        }
        if (openingTagCloses(position)) {
            emit(MakoTokenTypes.TAG_OPEN, position + 2, tagState(PHASE_NAME, closing = false, textTag = false))
            return
        }
        emit(MakoTokenTypes.PY_START, position + 2, MODE_PY)
    }

    private fun lexExpression() {
        lexDelimited(nesting = true, body = MakoTokenTypes.EXPR_BODY, stay = MODE_EXPR, allowBar = true)
    }

    private fun lexFilter() {
        lexDelimited(nesting = true, body = MakoTokenTypes.FILTER_BODY, stay = MODE_FILTER, allowBar = false)
    }

    private fun lexDelimited(nesting: Boolean, body: IElementType, stay: Int, allowBar: Boolean) {
        val current = buffer[position]
        if (allowBar && current == '|') {
            emit(MakoTokenTypes.FILTER_BAR, position + 1, MODE_FILTER)
            return
        }
        if (current == '}') {
            emit(MakoTokenTypes.EXPR_END, position + 1, MODE_TEXT)
            return
        }
        val end = scanCode(position, nesting) { index ->
            val ch = buffer[index]
            if (ch == '}' || (allowBar && ch == '|')) 1 else 0
        }
        if (end > position) emit(body, end, stay) else emit(TokenType.BAD_CHARACTER, position + 1, stay)
    }

    private fun lexPython() {
        if (pythonTerminator(position) == 2) {
            emit(MakoTokenTypes.PY_END, position + 2, MODE_TEXT)
            return
        }
        val end = scanCode(position, nesting = false, ::pythonTerminator)
        if (end > position) emit(MakoTokenTypes.PY_BODY, end, MODE_PY) else emit(TokenType.BAD_CHARACTER, position + 1, MODE_PY)
    }

    private fun lexModuleMark() {
        if (buffer[position] == '!') emit(MakoTokenTypes.MODULE_MARK, position + 1, MODE_PY)
        else emit(TokenType.BAD_CHARACTER, position + 1, MODE_PY)
    }

    private fun lexRaw(closer: String, body: IElementType, closingIsTag: Boolean) {
        val found = indexOf(closer, position)
        if (found == position) {
            if (closingIsTag) {
                emit(MakoTokenTypes.TAG_CLOSE_OPEN, position + 3, tagState(PHASE_NAME, closing = true, textTag = false))
            } else {
                emit(MakoTokenTypes.DOC_END, position + closer.length, MODE_TEXT)
            }
            return
        }
        val end = if (found < 0) endOffset else found
        val next = if (found < 0) MODE_TEXT else mode(state)
        emit(body, end, next)
    }

    private fun lexTag() {
        if (phase(state) == PHASE_NAME) {
            val start = position
            var index = start
            if (closing(state)) {
                while (index < endOffset && buffer[index] != '>' && !isTagSpace(buffer[index])) index++
            } else {
                while (index < endOffset && isKeywordChar(buffer[index])) index++
            }
            if (index == start) {
                emit(TokenType.BAD_CHARACTER, position + 1, MODE_TEXT)
                return
            }
            val keyword = buffer.subSequence(start, index).toString()
            val rawAfter = !closing(state) && keyword == "text"
            tokenType = MakoTokenTypes.TAG_KEYWORD
            tokenEnd = index
            pendingState = tagState(PHASE_AFTER, closing(state), rawAfter)
            return
        }
        lexTagAfter()
    }

    private fun lexTagAfter() {
        val ch = buffer[position]
        val keepClosing = closing(state)
        val keepText = textTag(state)
        fun stay() {
            pendingState = tagState(PHASE_AFTER, keepClosing, keepText)
        }
        when {
            isTagSpace(ch) -> {
                var index = position + 1
                while (index < endOffset && isTagSpace(buffer[index])) index++
                tokenType = MakoTokenTypes.TAG_WHITESPACE
                tokenEnd = index
                stay()
            }
            ch == ',' -> emit(MakoTokenTypes.COMMA, position + 1, tagState(PHASE_AFTER, keepClosing, keepText))
            ch == '=' -> emit(MakoTokenTypes.EQ, position + 1, tagState(PHASE_AFTER, keepClosing, keepText))
            ch == '"' || ch == '\'' -> {
                val end = scanQuoted(position)
                emit(MakoTokenTypes.ATTR_VALUE, if (end < 0) endOffset else end, tagState(PHASE_AFTER, keepClosing, keepText))
            }
            ch == '/' && position + 1 < endOffset && buffer[position + 1] == '>' -> emit(MakoTokenTypes.TAG_END, position + 2, MODE_TEXT)
            ch == '>' -> {
                val next = if (keepText && !keepClosing) MODE_RAW_TEXT else MODE_TEXT
                emit(MakoTokenTypes.TAG_END, position + 1, next)
            }
            isNameChar(ch) -> {
                var index = position + 1
                while (index < endOffset && isNameChar(buffer[index])) index++
                tokenType = MakoTokenTypes.ATTR_NAME
                tokenEnd = index
                stay()
            }
            else -> emit(TokenType.BAD_CHARACTER, position + 1, MODE_TEXT)
        }
    }

    private fun emit(type: IElementType, end: Int, nextState: Int) {
        tokenType = type
        tokenEnd = end.coerceAtMost(endOffset)
        pendingState = nextState
    }

    private fun openingTagCloses(openPos: Int): Boolean {
        var index = openPos + 2
        if (index >= endOffset || !isKeywordChar(buffer[index])) return false
        while (index < endOffset && isKeywordChar(buffer[index])) index++
        return findTagClose(index) >= 0
    }

    private fun findTagClose(start: Int): Int {
        var index = start
        while (index < endOffset) {
            val ch = buffer[index]
            when {
                isTagSpace(ch) || ch == ',' || ch == '=' -> index++
                ch == '"' || ch == '\'' -> {
                    val end = scanQuoted(index)
                    if (end < 0) return -1
                    index = end
                }
                ch == '/' && index + 1 < endOffset && buffer[index + 1] == '>' -> return index + 2
                ch == '>' -> return index + 1
                isNameChar(ch) -> {
                    index++
                    while (index < endOffset && isNameChar(buffer[index])) index++
                }
                else -> return -1
            }
        }
        return -1
    }

    private fun scanQuoted(start: Int): Int {
        val quote = buffer[start]
        var index = start + 1
        while (index < endOffset) {
            if (buffer[index] == quote) return index + 1
            index++
        }
        return -1
    }

    private fun scanCode(start: Int, nesting: Boolean, terminatorLength: (Int) -> Int): Int {
        var index = start
        var paren = 0
        var bracket = 0
        var brace = 0
        while (index < endOffset) {
            val ch = buffer[index]
            if (ch == '"' || ch == '\'') {
                val after = endOfString(index)
                if (after < 0) return endOffset
                index = after
                continue
            }
            if (ch == '#') {
                while (index < endOffset && buffer[index] != '\n') index++
                if (index < endOffset) index++
                continue
            }
            val terminator = terminatorLength(index)
            val atRoot = !nesting || (paren == 0 && bracket == 0 && brace == 0)
            if (terminator > 0 && atRoot) return index
            if (nesting) {
                when (ch) {
                    '(' -> paren++
                    ')' -> if (paren > 0) paren--
                    '[' -> bracket++
                    ']' -> if (bracket > 0) bracket--
                    '{' -> brace++
                    '}' -> if (brace > 0) brace--
                }
            }
            index++
        }
        return endOffset
    }

    private fun endOfString(start: Int): Int {
        val quote = buffer[start]
        val triple = start + 2 < endOffset && buffer[start + 1] == quote && buffer[start + 2] == quote
        var index = start + if (triple) 3 else 1
        while (index < endOffset) {
            if (buffer[index] == '\\') {
                index += 2
                continue
            }
            if (triple) {
                if (index + 2 < endOffset && buffer[index] == quote && buffer[index + 1] == quote && buffer[index + 2] == quote) {
                    return index + 3
                }
            } else if (buffer[index] == quote) {
                return index + 1
            }
            index++
        }
        return -1
    }

    private fun pythonTerminator(index: Int): Int {
        return if (index + 1 < endOffset && buffer[index] == '%' && buffer[index + 1] == '>') 2 else 0
    }

    private fun codingCookieEnd(): Int {
        if (endOffset == 0 || buffer[0] != '#') return -1
        var lineEnd = 0
        while (lineEnd < endOffset && buffer[lineEnd] != '\n' && buffer[lineEnd] != '\r') lineEnd++
        if (lineEnd >= endOffset) return -1
        val line = buffer.subSequence(0, lineEnd).toString()
        if (!CODING_COOKIE.matches(line)) return -1
        return lineEnd
    }

    private fun logicalLineEnd(start: Int): Int {
        var index = start
        while (index < endOffset) {
            if (buffer[index] == '\\' && index + 1 < endOffset && isLineBreak(index + 1)) {
                index = afterLineBreak(index + 1)
                continue
            }
            if (buffer[index] == '\n' || buffer[index] == '\r') break
            index++
        }
        return index
    }

    private fun atLineStart(index: Int): Boolean = index == 0 || buffer[index - 1] == '\n'

    private fun lineKind(index: Int): LineKind? {
        var cursor = index
        while (cursor < endOffset && (buffer[cursor] == ' ' || buffer[cursor] == '\t')) cursor++
        if (cursor >= endOffset) return null
        if (buffer[cursor] == '#' && cursor + 1 < endOffset && buffer[cursor + 1] == '#') return LineKind.COMMENT
        // `%%` is literal text. Keep both percent characters in the document.
        if (buffer[cursor] == '%' && !(cursor + 1 < endOffset && buffer[cursor + 1] == '%')) return LineKind.CONTROL
        return null
    }

    private fun isLineBreak(index: Int): Boolean = buffer[index] == '\n' || buffer[index] == '\r'

    private fun afterLineBreak(index: Int): Int {
        if (buffer[index] == '\r' && index + 1 < endOffset && buffer[index + 1] == '\n') return index + 2
        return index + 1
    }

    private fun startsWith(offset: Int, text: String): Boolean {
        if (offset + text.length > endOffset) return false
        for (index in text.indices) {
            if (buffer[offset + index] != text[index]) return false
        }
        return true
    }

    private fun indexOf(needle: String, from: Int): Int {
        val last = endOffset - needle.length
        var index = from
        while (index <= last) {
            if (startsWith(index, needle)) return index
            index++
        }
        return -1
    }

    private enum class LineKind { CONTROL, COMMENT }

    companion object {
        private const val MODE_MASK = 0x0F
        private const val MODE_TEXT = 0
        private const val MODE_EXPR = 1
        private const val MODE_FILTER = 2
        private const val MODE_PY = 3
        private const val MODE_MODULE_MARK = 4
        private const val MODE_RAW_TEXT = 5
        private const val MODE_RAW_DOC = 6
        private const val MODE_TAG = 7

        private const val PHASE_SHIFT = 4
        private const val PHASE_NAME = 1
        private const val PHASE_AFTER = 2
        private const val FLAG_CLOSING = 1 shl 7
        private const val FLAG_TEXT_TAG = 1 shl 8

        private val CODING_COOKIE = Regex("""#.*coding[:=]\s*[-\w.]+.*""")

        private fun mode(value: Int): Int = value and MODE_MASK

        private fun phase(value: Int): Int = (value shr PHASE_SHIFT) and 0x7

        private fun closing(value: Int): Boolean = value and FLAG_CLOSING != 0

        private fun textTag(value: Int): Boolean = value and FLAG_TEXT_TAG != 0

        private fun tagState(phase: Int, closing: Boolean, textTag: Boolean): Int {
            var value = MODE_TAG or (phase shl PHASE_SHIFT)
            if (closing) value = value or FLAG_CLOSING
            if (textTag) value = value or FLAG_TEXT_TAG
            return value
        }

        private fun isKeywordChar(ch: Char): Boolean = ch.isLetterOrDigit() || ch == '_' || ch == '.' || ch == ':'

        private fun isNameChar(ch: Char): Boolean = ch.isLetterOrDigit() || ch == '_'

        private fun isTagSpace(ch: Char): Boolean = ch == ' ' || ch == '\t' || ch == '\n' || ch == '\r' || ch == '\u000c'
    }
}
