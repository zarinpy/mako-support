package com.mako.support.lang

import com.intellij.icons.AllIcons
import com.intellij.openapi.fileTypes.LanguageFileType
import javax.swing.Icon

object MakoFileType : LanguageFileType(MakoLanguage) {
    override fun getName(): String = "Mako"

    override fun getDescription(): String = "Mako template"

    override fun getDefaultExtension(): String = "mako"

    override fun getIcon(): Icon = AllIcons.FileTypes.Html
}
