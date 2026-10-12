package com.mako.support.spellcheck

import com.intellij.psi.PsiElement
import com.intellij.spellchecker.tokenizer.SpellcheckingStrategy
import com.intellij.spellchecker.tokenizer.Tokenizer
import com.mako.support.lexer.MakoTokenTypes

class MakoSpellcheckingStrategy : SpellcheckingStrategy() {
    override fun getTokenizer(element: PsiElement): Tokenizer<*> {
        val type = element.node?.elementType ?: return EMPTY_TOKENIZER
        return when (type) {
            MakoTokenTypes.TEXT,
            MakoTokenTypes.COMMENT,
            MakoTokenTypes.CODING_COMMENT,
            MakoTokenTypes.DOC_BODY,
            -> TEXT_TOKENIZER
            MakoTokenTypes.ATTR_VALUE ->
                if (element.text.contains("\${")) EMPTY_TOKENIZER else TEXT_TOKENIZER
            else -> EMPTY_TOKENIZER
        }
    }
}
