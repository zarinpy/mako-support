package com.mako.support.psi

import com.intellij.extapi.psi.ASTWrapperPsiElement
import com.intellij.lang.ASTNode
import com.mako.support.inject.MakoInjectionHost

open class MakoElement(node: ASTNode) : ASTWrapperPsiElement(node)

class MakoTemplateText(node: ASTNode) : MakoElement(node)

class MakoExpression(node: ASTNode) : MakoInjectionHost(node)

class MakoFilterList(node: ASTNode) : MakoElement(node)

class MakoPythonBlock(node: ASTNode) : MakoInjectionHost(node)

class MakoModuleBlock(node: ASTNode) : MakoInjectionHost(node)

class MakoLineComment(node: ASTNode) : MakoElement(node)

class MakoCodingComment(node: ASTNode) : MakoElement(node)

class MakoDocComment(node: ASTNode) : MakoElement(node)

class MakoControlStatement(node: ASTNode) : MakoInjectionHost(node)

class MakoTag(node: ASTNode) : MakoElement(node)

class MakoClosingTag(node: ASTNode) : MakoElement(node)

class MakoAttribute(node: ASTNode) : MakoInjectionHost(node)
