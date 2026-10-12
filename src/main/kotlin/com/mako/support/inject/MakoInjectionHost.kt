package com.mako.support.inject

import com.intellij.lang.ASTNode
import com.intellij.psi.LiteralTextEscaper
import com.intellij.psi.PsiLanguageInjectionHost
import com.mako.support.psi.MakoElement

/**
 * A Mako node whose text can host a Python fragment.
 * The fragment prefix and suffix live in the injection, not in the template.
 */
abstract class MakoInjectionHost(node: ASTNode) : MakoElement(node), PsiLanguageInjectionHost {
    override fun isValidHost(): Boolean = true

    override fun updateText(text: String): PsiLanguageInjectionHost = this

    override fun createLiteralTextEscaper(): LiteralTextEscaper<out PsiLanguageInjectionHost> {
        return LiteralTextEscaper.createSimple(this)
    }
}
