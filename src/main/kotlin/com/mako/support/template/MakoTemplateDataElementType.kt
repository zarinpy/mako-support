package com.mako.support.template

import com.intellij.psi.templateLanguages.TemplateDataElementType
import com.intellij.psi.tree.OuterLanguageElementType
import com.mako.support.lang.MakoLanguage
import com.mako.support.lexer.MakoTokenTypes

/**
 * Re-parses [MakoTokenTypes.TEXT] with the template data language.
 * Every other Mako token is an outer range, so HTML tag matching skips expressions and tags.
 */
object MakoTemplateDataElementType : TemplateDataElementType(
    "MAKO_TEMPLATE_DATA",
    MakoLanguage,
    MakoTokenTypes.TEXT,
    OuterLanguageElementType("MAKO_FRAGMENT", MakoLanguage),
)
