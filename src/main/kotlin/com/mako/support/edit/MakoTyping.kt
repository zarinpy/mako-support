package com.mako.support.edit

import com.intellij.application.options.CodeStyle
import com.intellij.codeInsight.AutoPopupController
import com.intellij.codeInsight.editorActions.EnterHandler
import com.intellij.codeInsight.editorActions.TypedHandlerDelegate
import com.intellij.codeInsight.editorActions.enter.EnterHandlerDelegate
import com.intellij.injected.editor.DocumentWindow
import com.intellij.lang.injection.InjectedLanguageManager
import com.intellij.openapi.actionSystem.DataContext
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.editor.actionSystem.EditorActionHandler
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Ref
import com.intellij.psi.PsiDocumentManager
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import com.intellij.psi.impl.source.tree.injected.InjectedLanguageEditorUtil
import com.intellij.psi.util.PsiTreeUtil
import com.jetbrains.python.PythonLanguage
import com.jetbrains.python.editor.PythonEnterHandler
import com.mako.support.inject.controlKeyword
import com.mako.support.lexer.MakoTokenTypes
import com.mako.support.psi.MakoDocComment
import com.mako.support.psi.MakoExpression
import com.mako.support.psi.MakoModuleBlock
import com.mako.support.psi.MakoPythonBlock
import com.mako.support.psi.MakoTag

class MakoTypedHandler : TypedHandlerDelegate() {
    override fun charTyped(c: Char, project: Project, editor: Editor, file: PsiFile): Result {
        if (c != '%' && c != '>' && c != ':') return Result.CONTINUE
        val manager = InjectedLanguageManager.getInstance(project)
        val topEditor = InjectedLanguageEditorUtil.getTopLevelEditor(editor)
        val mako = makoPsi(manager.getTopLevelFile(file)) ?: return Result.CONTINUE
        val document = topEditor.document
        PsiDocumentManager.getInstance(project).commitDocument(document)
        val caret = topEditor.caretModel.offset
        when (c) {
            '%' -> schedulePercent(project, topEditor, document, caret)
            '>' -> closeTag(document, mako, caret)
            ':' -> closeControl(topEditor, mako, caret)
        }
        return Result.CONTINUE
    }

    private fun schedulePercent(project: Project, editor: Editor, document: com.intellij.openapi.editor.Document, caret: Int) {
        if (ApplicationManager.getApplication().isUnitTestMode) return
        val line = lineText(document.charsSequence, (caret - 1).coerceAtLeast(0))
        val first = line.indexOfFirst { !it.isWhitespace() }
        val control = first >= 0 && line[first] == '%' && !line.trimStart().startsWith("%%")
        val closesAngle = line.trimEnd().endsWith("<%")
        if (control || closesAngle) AutoPopupController.getInstance(project).scheduleAutoPopup(editor)
    }

    private fun closeTag(document: com.intellij.openapi.editor.Document, file: PsiFile, caret: Int) {
        val element = elementAt(file, caret) ?: return
        val opener: PsiElement
        val keyword: String
        when (element.node.elementType) {
            MakoTokenTypes.TAG_END -> {
                if (element.text == "/>") return
                val tag = PsiTreeUtil.getParentOfType(element, MakoTag::class.java, false) ?: return
                keyword = tag.node.findChildByType(MakoTokenTypes.TAG_KEYWORD)?.text ?: return
                opener = tag
            }
            MakoTokenTypes.DOC_START -> {
                keyword = "doc"
                opener = PsiTreeUtil.getParentOfType(element, MakoDocComment::class.java, false) ?: element
            }
            else -> return
        }
        if (!takesBody(keyword)) return
        val region = MakoOutline.of(file).regions.firstOrNull { it.anchor == opener }
        if (region != null && region.bodyEnd < region.rangeEnd) return
        if (region == null && opener is MakoDocComment && opener.node.findChildByType(MakoTokenTypes.DOC_END) != null) return
        document.insertString(caret, "</%$keyword>")
    }

    private fun closeControl(editor: Editor, file: PsiFile, caret: Int) {
        val document = editor.document
        val text = document.charsSequence
        if (caret <= 0 || text[caret - 1] != ':') return
        if (text.subSequence(caret, lineEnd(text, caret)).toString().isNotBlank()) return
        val keyword = controlKeyword(lineText(text, caret - 1)) ?: return
        if (keyword !in OPENERS) return
        val next = openControlBlock(document, caret - 1, CodeStyle.getIndentOptions(file).INDENT_SIZE)
        editor.caretModel.moveToOffset(next.coerceIn(0, document.textLength))
    }
}

class MakoEnterHandler : EnterHandlerDelegate {
    private val python = PythonEnterHandler()

    override fun invokeInsideIndent(newLineCharOffset: Int, editor: Editor, dataContext: DataContext): Boolean {
        val project = editor.project ?: return false
        val psi = PsiDocumentManager.getInstance(project).getPsiFile(editor.document) ?: return false
        return makoPsi(psi) != null && !psi.language.isKindOf(PythonLanguage.INSTANCE)
    }

    override fun preprocessEnter(
        file: PsiFile,
        editor: Editor,
        caretOffset: Ref<Int>,
        caretAdvance: Ref<Int>,
        dataContext: DataContext,
        originalHandler: EditorActionHandler?,
    ): EnterHandlerDelegate.Result {
        if (file.language.isKindOf(PythonLanguage.INSTANCE)) return EnterHandlerDelegate.Result.Continue
        val project = file.project
        val topEditor = InjectedLanguageEditorUtil.getTopLevelEditor(editor)
        PsiDocumentManager.getInstance(project).commitDocument(topEditor.document)
        val mako = makoPsi(file) ?: return EnterHandlerDelegate.Result.Continue
        val caret = topEditor.caretModel.offset
        if (forwardPythonEnter(mako, topEditor, caret, dataContext, originalHandler)) {
            return EnterHandlerDelegate.Result.Stop
        }
        val outline = MakoOutline.of(mako)
        val outward = outline.regionsOutward(caret)
        val raw = outward.firstOrNull { it.kind == RegionKind.TEXT || it.kind == RegionKind.DOC }
        if (raw != null && caret in raw.bodyStart..raw.bodyEnd) {
            topEditor.document.insertString(caret, "\n")
            topEditor.caretModel.moveToOffset(caret + 1)
            return EnterHandlerDelegate.Result.Stop
        }
        val block = outward.firstOrNull {
            it.kind in INDENTED && caret in it.bodyStart..it.bodyEnd
        }
        if (block != null) {
            val line = lineText(topEditor.document.charsSequence, block.rangeStart)
            val indent = leadingWhitespace(line) + " ".repeat(CodeStyle.getIndentOptions(mako).INDENT_SIZE.coerceAtLeast(0))
            topEditor.document.insertString(caret, "\n$indent")
            topEditor.caretModel.moveToOffset(caret + 1 + indent.length)
            return EnterHandlerDelegate.Result.Stop
        }
        return EnterHandlerDelegate.Result.Continue
    }

    override fun postProcessEnter(file: PsiFile, editor: Editor, dataContext: DataContext): EnterHandlerDelegate.Result {
        return EnterHandlerDelegate.Result.Continue
    }

    /**
     * Python's enter handler writes into the injected document. The caret Ref
     * passed in here is checked against the host document, so Python gets its own.
     */
    private fun forwardPythonEnter(
        mako: PsiFile,
        topEditor: Editor,
        caret: Int,
        dataContext: DataContext,
        originalHandler: EditorActionHandler?,
    ): Boolean {
        if (injectionHostAt(mako, caret) == null) return false
        val injectedEditor = InjectedLanguageEditorUtil.getEditorForInjectedLanguageNoCommit(topEditor, mako)
        if (injectedEditor == null || injectedEditor == topEditor) return false
        val project = mako.project
        val document = injectedEditor.document
        val injectedFile = PsiDocumentManager.getInstance(project).getPsiFile(document) ?: return false
        if (!injectedFile.language.isKindOf(PythonLanguage.INSTANCE)) return false
        val window = document as? DocumentWindow
        val injectedAt = window?.hostToInjected(caret)?.takeIf { it >= 0 } ?: injectedEditor.caretModel.offset
        injectedEditor.caretModel.moveToOffset(injectedAt.coerceIn(0, document.textLength))
        val injectedCaret = Ref.create(injectedEditor.caretModel.offset)
        val injectedAdvance = Ref.create(0)
        val result = python.preprocessEnter(
            injectedFile,
            injectedEditor,
            injectedCaret,
            injectedAdvance,
            dataContext,
            originalHandler,
        )
        if (result == EnterHandlerDelegate.Result.Stop) return true
        // PythonEnterHandler unwraps an injected editor to the host file and
        // returns Continue, because the host is Mako. Insert the break on the
        // shred that contains the host caret, then indent that line as Python.
        val hostCaret = topEditor.caretModel.offset
        val at = window?.hostToInjected(hostCaret)?.takeIf { it >= 0 } ?: injectedEditor.caretModel.offset
        document.insertString(at, "\n")
        injectedEditor.caretModel.moveToOffset(at + 1)
        PsiDocumentManager.getInstance(project).commitDocument(document)
        python.postProcessEnter(injectedFile, injectedEditor, dataContext)
        val adjusted = EnterHandler.adjustLineIndentNoCommit(
            PythonLanguage.getInstance(),
            document,
            injectedEditor,
            injectedEditor.caretModel.offset,
        )
        if (adjusted >= 0) injectedEditor.caretModel.moveToOffset(adjusted.coerceIn(0, document.textLength))
        return true
    }

    private fun injectionHostAt(file: PsiFile, caret: Int): PsiElement? {
        val offsets = listOf(caret, caret - 1).filter { it >= 0 }
        for (offset in offsets) {
            val element = file.findElementAt(offset) ?: continue
            val host = PsiTreeUtil.getParentOfType(element, MakoPythonBlock::class.java, false)
                ?: PsiTreeUtil.getParentOfType(element, MakoModuleBlock::class.java, false)
                ?: PsiTreeUtil.getParentOfType(element, MakoExpression::class.java, false)
            if (host != null) return host
        }
        return null
    }

    private companion object {
        val INDENTED = setOf(
            RegionKind.CONTROL,
            RegionKind.DEF,
            RegionKind.BLOCK,
            RegionKind.NAMESPACE,
            RegionKind.CALL,
        )
    }
}
