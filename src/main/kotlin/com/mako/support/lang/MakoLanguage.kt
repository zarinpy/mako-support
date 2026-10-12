package com.mako.support.lang

import com.intellij.lang.Language
import com.intellij.psi.templateLanguages.TemplateLanguage

object MakoLanguage : Language("Mako"), TemplateLanguage {
    override fun getDisplayName(): String = "Mako"
}
