package com.mako.support.parser

import com.intellij.lang.ASTNode
import com.intellij.lang.ParserDefinition
import com.intellij.lang.PsiParser
import com.intellij.lexer.Lexer
import com.intellij.openapi.project.Project
import com.intellij.psi.FileViewProvider
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import com.intellij.psi.tree.IFileElementType
import com.intellij.psi.tree.TokenSet
import com.mako.support.lexer.MakoLexer
import com.mako.support.lexer.MakoTokenTypes
import com.mako.support.psi.MakoAttribute
import com.mako.support.psi.MakoClosingTag
import com.mako.support.psi.MakoCodingComment
import com.mako.support.psi.MakoControlStatement
import com.mako.support.psi.MakoDocComment
import com.mako.support.psi.MakoExpression
import com.mako.support.psi.MakoFile
import com.mako.support.psi.MakoFilterList
import com.mako.support.psi.MakoLineComment
import com.mako.support.psi.MakoModuleBlock
import com.mako.support.psi.MakoPythonBlock
import com.mako.support.psi.MakoTag
import com.mako.support.psi.MakoTemplateText
import com.intellij.extapi.psi.ASTWrapperPsiElement

class MakoParserDefinition : ParserDefinition {
    override fun createLexer(project: Project): Lexer = MakoLexer()

    override fun createParser(project: Project): PsiParser = MakoParser()

    override fun getFileNodeType(): IFileElementType = MakoElementTypes.FILE

    override fun getWhitespaceTokens(): TokenSet = TokenSet.EMPTY

    override fun getCommentTokens(): TokenSet = TokenSet.create(
        MakoTokenTypes.COMMENT,
        MakoTokenTypes.CODING_COMMENT,
        MakoTokenTypes.DOC_START,
        MakoTokenTypes.DOC_BODY,
        MakoTokenTypes.DOC_END,
    )

    override fun getStringLiteralElements(): TokenSet = TokenSet.create(MakoTokenTypes.ATTR_VALUE)

    override fun createElement(node: ASTNode): PsiElement = when (node.elementType) {
        MakoElementTypes.TEMPLATE_TEXT -> MakoTemplateText(node)
        MakoElementTypes.EXPRESSION -> MakoExpression(node)
        MakoElementTypes.FILTER_LIST -> MakoFilterList(node)
        MakoElementTypes.PYTHON_BLOCK -> MakoPythonBlock(node)
        MakoElementTypes.MODULE_BLOCK -> MakoModuleBlock(node)
        MakoElementTypes.LINE_COMMENT -> MakoLineComment(node)
        MakoElementTypes.CODING_COMMENT -> MakoCodingComment(node)
        MakoElementTypes.DOC_COMMENT -> MakoDocComment(node)
        MakoElementTypes.CONTROL_STATEMENT -> MakoControlStatement(node)
        MakoElementTypes.TAG -> MakoTag(node)
        MakoElementTypes.CLOSING_TAG -> MakoClosingTag(node)
        MakoElementTypes.ATTRIBUTE -> MakoAttribute(node)
        else -> ASTWrapperPsiElement(node)
    }

    override fun createFile(viewProvider: FileViewProvider): PsiFile = MakoFile(viewProvider)
}
