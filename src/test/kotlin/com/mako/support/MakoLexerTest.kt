package com.mako.support

import com.mako.support.lexer.MakoLexer

class MakoLexerTest : MakoPlatformTestCase() {
    fun testTokensCoverTheSource() {
        val text = """
            # coding: utf-8
            <%page args="x"/>
            Hello ${'$'}{name | h}
              % if x:
              %% kept
            <%!
            x = 1
            %>
            <%text>${'$'}{raw}</%text>
            <%doc>${'$'}{secret}</%doc>
            <div class="${'$'}{cls}">
        """.trimIndent()
        val tokens = tokenize(text)
        assertEquals(text, tokens.joinToString("") { it.second })
        assertTrue(tokens.any { it.first == "TEXT" && it.second.contains("\${raw}") })
        assertTrue(tokens.any { it.first == "DOC_BODY" && it.second.contains("\${secret}") })
        assertTrue(tokens.any { it.first == "TEXT" && it.second.contains("%% kept") })
    }

    fun testExpressionFilterAndStrings() {
        assertEquals(
            listOf(
                "EXPR_START" to "\${",
                "EXPR_BODY" to "name ",
                "FILTER_BAR" to "|",
                "FILTER_BODY" to " h",
                "EXPR_END" to "}",
            ),
            tokenize("\${name | h}"),
        )
        assertEquals(
            listOf(
                "EXPR_START" to "\${",
                "EXPR_BODY" to "f(a, {\"b\": 1}) ",
                "FILTER_BAR" to "|",
                "FILTER_BODY" to " h",
                "EXPR_END" to "}",
            ),
            tokenize("\${f(a, {\"b\": 1}) | h}"),
        )
        assertEquals(
            listOf(
                "EXPR_START" to "\${",
                "EXPR_BODY" to "\"a|b\"",
                "EXPR_END" to "}",
            ),
            tokenize("\${\"a|b\"}"),
        )
        assertEquals(
            listOf(
                "EXPR_START" to "\${",
                "EXPR_BODY" to "\"a}b\"",
                "EXPR_END" to "}",
            ),
            tokenize("\${\"a}b\"}"),
        )
        assertEquals(listOf("TEXT" to "$$"), tokenize("$$"))
    }

    fun testControlCommentPercentAndNewlineEscape() {
        assertEquals(
            listOf("CONTROL" to "  % if x:", "TEXT" to "\n"),
            tokenize("  % if x:\n"),
        )
        assertEquals(
            listOf("COMMENT" to "  ## note", "TEXT" to "\n"),
            tokenize("  ## note\n"),
        )
        assertEquals(
            listOf("TEXT" to "  %%hello\n"),
            tokenize("  %%hello\n"),
        )
        assertEquals(
            listOf("TEXT" to "line", "NEWLINE_ESCAPE" to "\\", "TEXT" to "\nnext"),
            tokenize("line\\\nnext"),
        )
        assertEquals(
            listOf("CONTROL" to "% if \\\n x:", "TEXT" to "\n"),
            tokenize("% if \\\n x:\n"),
        )
        assertEquals(
            listOf("CODING_COMMENT" to "# coding: utf-8", "TEXT" to "\nHi"),
            tokenize("# coding: utf-8\nHi"),
        )
    }

    fun testPythonTagsAndRawRegions() {
        assertEquals(
            listOf(
                "PY_START" to "<%",
                "PY_BODY" to " x = \"%>\" ",
                "PY_END" to "%>",
            ),
            tokenize("<% x = \"%>\" %>"),
        )
        assertEquals(
            listOf(
                "PY_START" to "<%",
                "MODULE_MARK" to "!",
                "PY_BODY" to " x ",
                "PY_END" to "%>",
            ),
            tokenize("<%! x %>"),
        )
        assertEquals(
            listOf(
                "TAG_OPEN" to "<%",
                "TAG_KEYWORD" to "include",
                "TAG_WHITESPACE" to " ",
                "ATTR_NAME" to "file",
                "EQ" to "=",
                "ATTR_VALUE" to "\"a.html\"",
                "TAG_END" to "/>",
            ),
            tokenize("<%include file=\"a.html\"/>"),
        )
        assertEquals(
            listOf(
                "TAG_OPEN" to "<%",
                "TAG_KEYWORD" to "text",
                "TAG_END" to ">",
                "TEXT" to "\${x}",
                "TAG_CLOSE_OPEN" to "</%",
                "TAG_KEYWORD" to "text",
                "TAG_END" to ">",
            ),
            tokenize("<%text>\${x}</%text>"),
        )
        assertEquals(
            listOf(
                "DOC_START" to "<%doc>",
                "DOC_BODY" to " \${x} ",
                "DOC_END" to "</%doc>",
            ),
            tokenize("<%doc> \${x} </%doc>"),
        )
    }

    private fun tokenize(text: String): List<Pair<String, String>> {
        val lexer = MakoLexer()
        lexer.start(text)
        val tokens = mutableListOf<Pair<String, String>>()
        var previousEnd = 0
        var guard = 0
        while (lexer.tokenType != null) {
            assertEquals(previousEnd, lexer.tokenStart)
            val chunk = text.substring(lexer.tokenStart, lexer.tokenEnd)
            assertTrue(chunk.isNotEmpty())
            tokens += lexer.tokenType.toString() to chunk
            previousEnd = lexer.tokenEnd
            lexer.advance()
            guard++
            assertTrue(guard <= text.length + 2)
        }
        assertEquals(text.length, previousEnd)
        return tokens
    }
}
