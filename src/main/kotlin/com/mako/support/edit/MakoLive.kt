package com.mako.support.edit

import com.intellij.codeInsight.template.TemplateActionContext
import com.intellij.codeInsight.template.TemplateContextType
import com.intellij.codeInsight.template.impl.DefaultLiveTemplatesProvider
import com.intellij.lang.surroundWith.SurroundDescriptor
import com.intellij.lang.surroundWith.Surrounder
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.TextRange
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import com.intellij.psi.util.PsiTreeUtil

class MakoLiveTemplatesProvider : DefaultLiveTemplatesProvider {
    override fun getDefaultLiveTemplateFiles(): Array<String> = arrayOf("liveTemplates/Mako")

    override fun getHiddenLiveTemplateFiles(): Array<String> = emptyArray()
}

class MakoTemplateContext : TemplateContextType("Mako", "Mako") {
    override fun isInContext(file: PsiFile, offset: Int): Boolean = makoPsi(file) != null

    override fun isInContext(context: TemplateActionContext): Boolean = isInContext(context.file, context.startOffset)
}

class MakoSurroundDescriptor : SurroundDescriptor {
    override fun getElementsToSurround(file: PsiFile, startOffset: Int, endOffset: Int): Array<PsiElement> {
        if (makoPsi(file) == null || startOffset >= endOffset) return PsiElement.EMPTY_ARRAY
        val start = file.findElementAt(startOffset) ?: return PsiElement.EMPTY_ARRAY
        val end = file.findElementAt((endOffset - 1).coerceAtLeast(startOffset)) ?: return PsiElement.EMPTY_ARRAY
        val parent = PsiTreeUtil.findCommonParent(start, end) ?: return PsiElement.EMPTY_ARRAY
        return arrayOf(parent)
    }

    override fun getSurrounders(): Array<Surrounder> = SURROUNDERS

    override fun isExclusive(): Boolean = false

    private companion object {
        val SURROUNDERS = arrayOf<Surrounder>(
            MakoSurrounder("% if") { selected -> "% if :\n$selected\n% endif" to "% if ".length },
            MakoSurrounder("% for") { selected -> "% for :\n$selected\n% endfor" to "% for ".length },
            MakoSurrounder("<%def>") { selected -> "<%def name=\"\">\n$selected\n</%def>" to "<%def name=\"".length },
            MakoSurrounder("<%block>") { selected -> "<%block name=\"\">\n$selected\n</%block>" to "<%block name=\"".length },
            MakoSurrounder("<%text>") { selected -> "<%text>\n$selected\n</%text>" to "<%text>\n".length },
        )
    }
}

private class MakoSurrounder(
    private val description: String,
    private val render: (String) -> Pair<String, Int>,
) : Surrounder {
    override fun getTemplateDescription(): String = description

    override fun isApplicable(elements: Array<out PsiElement>): Boolean = elements.isNotEmpty()

    override fun surroundElements(project: Project, editor: Editor, elements: Array<out PsiElement>): TextRange? {
        if (elements.isEmpty()) return null
        val document = editor.document
        val start = if (editor.selectionModel.hasSelection()) {
            editor.selectionModel.selectionStart
        } else {
            elements.minOf { it.textRange.startOffset }
        }
        val end = if (editor.selectionModel.hasSelection()) {
            editor.selectionModel.selectionEnd
        } else {
            elements.maxOf { it.textRange.endOffset }
        }
        if (start >= end || end > document.textLength) return null
        val selected = document.charsSequence.subSequence(start, end).toString()
        val (text, relativeCaret) = render(selected)
        document.replaceString(start, end, text)
        val caret = (start + relativeCaret).coerceIn(0, document.textLength)
        editor.caretModel.moveToOffset(caret)
        editor.selectionModel.removeSelection()
        return TextRange(caret, caret)
    }
}
