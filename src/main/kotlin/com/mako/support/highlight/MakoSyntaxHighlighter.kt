package com.mako.support.highlight

import com.intellij.lexer.Lexer
import com.intellij.openapi.editor.DefaultLanguageHighlighterColors
import com.intellij.openapi.editor.HighlighterColors
import com.intellij.openapi.editor.colors.TextAttributesKey
import com.intellij.openapi.fileTypes.SyntaxHighlighterBase
import com.intellij.psi.TokenType
import com.intellij.psi.tree.IElementType
import com.mako.support.lexer.MakoLexer
import com.mako.support.lexer.MakoTokenTypes

class MakoSyntaxHighlighter : SyntaxHighlighterBase() {
    override fun getHighlightingLexer(): Lexer = MakoLexer()

    override fun getTokenHighlights(tokenType: IElementType): Array<TextAttributesKey> = when (tokenType) {
        MakoTokenTypes.TEXT, MakoTokenTypes.TAG_WHITESPACE -> TextAttributesKey.EMPTY_ARRAY
        MakoTokenTypes.EXPR_START, MakoTokenTypes.EXPR_END, MakoTokenTypes.EXPR_BODY -> EXPRESSION
        MakoTokenTypes.FILTER_BAR, MakoTokenTypes.FILTER_BODY -> FILTER
        MakoTokenTypes.TAG_OPEN, MakoTokenTypes.TAG_CLOSE_OPEN, MakoTokenTypes.TAG_END, MakoTokenTypes.TAG_KEYWORD -> TAG
        MakoTokenTypes.ATTR_NAME -> ATTRIBUTE
        MakoTokenTypes.ATTR_VALUE -> VALUE
        MakoTokenTypes.EQ, MakoTokenTypes.COMMA -> OPERATION
        MakoTokenTypes.CONTROL -> CONTROL
        MakoTokenTypes.COMMENT, MakoTokenTypes.CODING_COMMENT -> LINE_COMMENT
        MakoTokenTypes.DOC_START, MakoTokenTypes.DOC_BODY, MakoTokenTypes.DOC_END -> DOC_COMMENT
        MakoTokenTypes.PY_START, MakoTokenTypes.PY_END, MakoTokenTypes.PY_BODY, MakoTokenTypes.MODULE_MARK -> PYTHON
        MakoTokenTypes.NEWLINE_ESCAPE -> ESCAPE
        TokenType.BAD_CHARACTER -> BAD
        else -> TextAttributesKey.EMPTY_ARRAY
    }

    companion object {
        private val EXPRESSION = arrayOf(DefaultLanguageHighlighterColors.TEMPLATE_LANGUAGE_COLOR)
        private val FILTER = arrayOf(DefaultLanguageHighlighterColors.METADATA)
        private val TAG = arrayOf(DefaultLanguageHighlighterColors.KEYWORD)
        private val ATTRIBUTE = arrayOf(DefaultLanguageHighlighterColors.MARKUP_ATTRIBUTE)
        private val VALUE = arrayOf(DefaultLanguageHighlighterColors.STRING)
        private val OPERATION = arrayOf(DefaultLanguageHighlighterColors.OPERATION_SIGN)
        private val CONTROL = arrayOf(DefaultLanguageHighlighterColors.KEYWORD)
        private val LINE_COMMENT = arrayOf(DefaultLanguageHighlighterColors.LINE_COMMENT)
        private val DOC_COMMENT = arrayOf(DefaultLanguageHighlighterColors.DOC_COMMENT)
        private val PYTHON = arrayOf(DefaultLanguageHighlighterColors.TEMPLATE_LANGUAGE_COLOR)
        private val ESCAPE = arrayOf(DefaultLanguageHighlighterColors.VALID_STRING_ESCAPE)
        private val BAD = arrayOf(HighlighterColors.BAD_CHARACTER)
    }
}
