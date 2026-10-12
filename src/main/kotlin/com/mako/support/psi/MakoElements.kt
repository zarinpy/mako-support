package com.mako.support.psi

import com.intellij.extapi.psi.ASTWrapperPsiElement
import com.intellij.lang.ASTNode

open class MakoElement(node: ASTNode) : ASTWrapperPsiElement(node)

class MakoTemplateText(node: ASTNode) : MakoElement(node)

class MakoExpression(node: ASTNode) : MakoElement(node)

class MakoFilterList(node: ASTNode) : MakoElement(node)

class MakoPythonBlock(node: ASTNode) : MakoElement(node)

class MakoModuleBlock(node: ASTNode) : MakoElement(node)

class MakoLineComment(node: ASTNode) : MakoElement(node)

class MakoCodingComment(node: ASTNode) : MakoElement(node)

class MakoDocComment(node: ASTNode) : MakoElement(node)

class MakoControlStatement(node: ASTNode) : MakoElement(node)

class MakoTag(node: ASTNode) : MakoElement(node)

class MakoClosingTag(node: ASTNode) : MakoElement(node)

class MakoAttribute(node: ASTNode) : MakoElement(node)
