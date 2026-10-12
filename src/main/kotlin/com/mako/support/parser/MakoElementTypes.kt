package com.mako.support.parser

import com.intellij.psi.tree.IElementType
import com.intellij.psi.tree.IFileElementType
import com.mako.support.lang.MakoLanguage

class MakoElementType(debugName: String) : IElementType(debugName, MakoLanguage)

object MakoElementTypes {
    val FILE: IFileElementType = IFileElementType("MAKO_FILE", MakoLanguage)

    val TEMPLATE_TEXT: IElementType = MakoElementType("MAKO_TEMPLATE_TEXT")
    val EXPRESSION: IElementType = MakoElementType("MAKO_EXPRESSION")
    val FILTER_LIST: IElementType = MakoElementType("MAKO_FILTER_LIST")
    val PYTHON_BLOCK: IElementType = MakoElementType("MAKO_PYTHON_BLOCK")
    val MODULE_BLOCK: IElementType = MakoElementType("MAKO_MODULE_BLOCK")
    val LINE_COMMENT: IElementType = MakoElementType("MAKO_LINE_COMMENT")
    val CODING_COMMENT: IElementType = MakoElementType("MAKO_CODING_COMMENT")
    val DOC_COMMENT: IElementType = MakoElementType("MAKO_DOC_COMMENT")
    val CONTROL_STATEMENT: IElementType = MakoElementType("MAKO_CONTROL_STATEMENT")
    val TAG: IElementType = MakoElementType("MAKO_TAG")
    val CLOSING_TAG: IElementType = MakoElementType("MAKO_CLOSING_TAG")
    val ATTRIBUTE: IElementType = MakoElementType("MAKO_ATTRIBUTE")
}
