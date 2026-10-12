package com.mako.support.highlight

import com.intellij.lang.html.HTMLLanguage
import com.intellij.openapi.editor.colors.EditorColorsScheme
import com.intellij.openapi.editor.ex.util.LayerDescriptor
import com.intellij.openapi.editor.ex.util.LayeredLexerEditorHighlighter
import com.intellij.openapi.fileTypes.FileTypes
import com.intellij.openapi.fileTypes.SyntaxHighlighterFactory
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.templateLanguages.TemplateDataHighlighterWrapper
import com.mako.support.lexer.MakoTokenTypes
import com.mako.support.template.MakoFileViewProvider

class MakoEditorHighlighter(
    project: Project?,
    virtualFile: VirtualFile?,
    colors: EditorColorsScheme,
) : LayeredLexerEditorHighlighter(MakoSyntaxHighlighter(), colors) {
    init {
        val dataLanguage = if (project != null && virtualFile != null) {
            MakoFileViewProvider.templateDataLanguage(virtualFile, project)
        } else {
            HTMLLanguage.INSTANCE
        }
        val fileType = dataLanguage.associatedFileType ?: FileTypes.PLAIN_TEXT
        val dataHighlighter = SyntaxHighlighterFactory.getSyntaxHighlighter(fileType, project, virtualFile)
        if (dataHighlighter != null) {
            registerLayer(MakoTokenTypes.TEXT, LayerDescriptor(TemplateDataHighlighterWrapper(dataHighlighter), ""))
        }
    }
}
