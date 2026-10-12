package com.mako.support.parser

import com.intellij.lang.ASTNode
import com.intellij.lang.PsiBuilder
import com.intellij.lang.PsiParser
import com.intellij.psi.tree.IElementType
import com.mako.support.lexer.MakoTokenTypes

class MakoParser : PsiParser {
    override fun parse(root: IElementType, builder: PsiBuilder): ASTNode {
        val file = builder.mark()
        while (!builder.eof()) {
            when (builder.tokenType) {
                MakoTokenTypes.TEXT, MakoTokenTypes.NEWLINE_ESCAPE -> parseText(builder)
                MakoTokenTypes.EXPR_START -> parseExpression(builder)
                MakoTokenTypes.COMMENT -> parseOne(builder, MakoElementTypes.LINE_COMMENT)
                MakoTokenTypes.CODING_COMMENT -> parseOne(builder, MakoElementTypes.CODING_COMMENT)
                MakoTokenTypes.DOC_START -> parseDoc(builder)
                MakoTokenTypes.PY_START -> parsePython(builder)
                MakoTokenTypes.TAG_OPEN -> parseTag(builder, closing = false)
                MakoTokenTypes.TAG_CLOSE_OPEN -> parseTag(builder, closing = true)
                MakoTokenTypes.CONTROL -> parseOne(builder, MakoElementTypes.CONTROL_STATEMENT)
                else -> parseOneError(builder, "Unexpected token")
            }
        }
        file.done(root)
        return builder.treeBuilt
    }

    private fun parseText(builder: PsiBuilder) {
        val marker = builder.mark()
        while (builder.tokenType == MakoTokenTypes.TEXT || builder.tokenType == MakoTokenTypes.NEWLINE_ESCAPE) {
            builder.advanceLexer()
        }
        marker.done(MakoElementTypes.TEMPLATE_TEXT)
    }

    private fun parseExpression(builder: PsiBuilder) {
        val marker = builder.mark()
        builder.advanceLexer()
        if (builder.tokenType == MakoTokenTypes.EXPR_BODY) builder.advanceLexer()
        if (builder.tokenType == MakoTokenTypes.FILTER_BAR) {
            val filter = builder.mark()
            builder.advanceLexer()
            if (builder.tokenType == MakoTokenTypes.FILTER_BODY) builder.advanceLexer()
            filter.done(MakoElementTypes.FILTER_LIST)
        }
        if (builder.tokenType == MakoTokenTypes.EXPR_END) builder.advanceLexer() else builder.error("Unclosed expression")
        marker.done(MakoElementTypes.EXPRESSION)
    }

    private fun parsePython(builder: PsiBuilder) {
        val marker = builder.mark()
        builder.advanceLexer()
        val module = builder.tokenType == MakoTokenTypes.MODULE_MARK
        if (module) builder.advanceLexer()
        if (builder.tokenType == MakoTokenTypes.PY_BODY) builder.advanceLexer()
        if (builder.tokenType == MakoTokenTypes.PY_END) builder.advanceLexer() else builder.error("Unclosed Python block")
        marker.done(if (module) MakoElementTypes.MODULE_BLOCK else MakoElementTypes.PYTHON_BLOCK)
    }

    private fun parseDoc(builder: PsiBuilder) {
        val marker = builder.mark()
        builder.advanceLexer()
        if (builder.tokenType == MakoTokenTypes.DOC_BODY) builder.advanceLexer()
        if (builder.tokenType == MakoTokenTypes.DOC_END) builder.advanceLexer() else builder.error("Unclosed doc comment")
        marker.done(MakoElementTypes.DOC_COMMENT)
    }

    private fun parseTag(builder: PsiBuilder, closing: Boolean) {
        val marker = builder.mark()
        builder.advanceLexer()
        if (builder.tokenType == MakoTokenTypes.TAG_KEYWORD) builder.advanceLexer()
        var closed = false
        while (!builder.eof()) {
            when (builder.tokenType) {
                MakoTokenTypes.TAG_WHITESPACE, MakoTokenTypes.COMMA -> builder.advanceLexer()
                MakoTokenTypes.ATTR_NAME -> parseAttribute(builder)
                MakoTokenTypes.EQ, MakoTokenTypes.ATTR_VALUE -> builder.advanceLexer()
                MakoTokenTypes.TAG_END -> {
                    builder.advanceLexer()
                    closed = true
                    break
                }
                else -> break
            }
        }
        if (!closed) builder.error(if (closing) "Unclosed closing tag" else "Unclosed tag")
        marker.done(if (closing) MakoElementTypes.CLOSING_TAG else MakoElementTypes.TAG)
    }

    private fun parseAttribute(builder: PsiBuilder) {
        val marker = builder.mark()
        builder.advanceLexer()
        if (builder.tokenType == MakoTokenTypes.EQ) builder.advanceLexer()
        if (builder.tokenType == MakoTokenTypes.ATTR_VALUE) builder.advanceLexer()
        marker.done(MakoElementTypes.ATTRIBUTE)
    }

    private fun parseOne(builder: PsiBuilder, type: IElementType) {
        val marker = builder.mark()
        builder.advanceLexer()
        marker.done(type)
    }

    private fun parseOneError(builder: PsiBuilder, message: String) {
        val marker = builder.mark()
        builder.advanceLexer()
        marker.error(message)
    }
}
