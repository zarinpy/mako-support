package com.mako.support.lexer

import com.intellij.psi.tree.IElementType
import com.mako.support.lang.MakoLanguage

class MakoTokenType(debugName: String) : IElementType(debugName, MakoLanguage)

object MakoTokenTypes {
    val TEXT: IElementType = MakoTokenType("TEXT")
    val NEWLINE_ESCAPE: IElementType = MakoTokenType("NEWLINE_ESCAPE")

    val EXPR_START: IElementType = MakoTokenType("EXPR_START")
    val EXPR_BODY: IElementType = MakoTokenType("EXPR_BODY")
    val FILTER_BAR: IElementType = MakoTokenType("FILTER_BAR")
    val FILTER_BODY: IElementType = MakoTokenType("FILTER_BODY")
    val EXPR_END: IElementType = MakoTokenType("EXPR_END")

    val COMMENT: IElementType = MakoTokenType("COMMENT")
    val CODING_COMMENT: IElementType = MakoTokenType("CODING_COMMENT")
    val DOC_START: IElementType = MakoTokenType("DOC_START")
    val DOC_BODY: IElementType = MakoTokenType("DOC_BODY")
    val DOC_END: IElementType = MakoTokenType("DOC_END")

    val PY_START: IElementType = MakoTokenType("PY_START")
    val MODULE_MARK: IElementType = MakoTokenType("MODULE_MARK")
    val PY_BODY: IElementType = MakoTokenType("PY_BODY")
    val PY_END: IElementType = MakoTokenType("PY_END")

    val TAG_OPEN: IElementType = MakoTokenType("TAG_OPEN")
    val TAG_CLOSE_OPEN: IElementType = MakoTokenType("TAG_CLOSE_OPEN")
    val TAG_KEYWORD: IElementType = MakoTokenType("TAG_KEYWORD")
    val TAG_WHITESPACE: IElementType = MakoTokenType("TAG_WHITESPACE")
    val ATTR_NAME: IElementType = MakoTokenType("ATTR_NAME")
    val EQ: IElementType = MakoTokenType("EQ")
    val ATTR_VALUE: IElementType = MakoTokenType("ATTR_VALUE")
    val COMMA: IElementType = MakoTokenType("COMMA")
    val TAG_END: IElementType = MakoTokenType("TAG_END")

    val CONTROL: IElementType = MakoTokenType("CONTROL")
}
