package com.mako.support

import com.intellij.application.options.CodeStyle
import com.intellij.lang.injection.InjectedLanguageManager
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.psi.PsiDocumentManager
import com.intellij.psi.codeStyle.CodeStyleManager
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.codeInsight.template.TemplateManager
import com.intellij.codeInsight.template.impl.TemplateSettings
import com.intellij.spellchecker.tokenizer.SpellcheckingStrategy
import com.mako.support.edit.MakoSurroundDescriptor
import com.mako.support.psi.MakoExpression
import com.mako.support.psi.MakoPythonBlock
import com.mako.support.spellcheck.MakoSpellcheckingStrategy

class MakoTemplateAndFormatTest : MakoPlatformTestCase() {
    fun testIfTemplateExpands() {
        val template = TemplateSettings.getInstance().getTemplate("if", "Mako")
        assertNotNull(template)
        myFixture.configureByText("page.mako", "<caret>")
        WriteCommandAction.runWriteCommandAction(project) {
            TemplateManager.getInstance(project).startTemplate(myFixture.editor, template!!)
        }
        val text = myFixture.editor.document.text
        assertTrue(text, text.contains("% if"))
        assertTrue(text, text.contains("% endif"))
    }

    fun testSurroundSelectionWithTextTag() {
        myFixture.configureByText("page.mako", "keep")
        myFixture.editor.selectionModel.setSelection(0, 4)
        val descriptor = MakoSurroundDescriptor()
        val elements = descriptor.getElementsToSurround(myFixture.file, 0, 4)
        assertFalse(elements.isEmpty())
        assertFalse(descriptor.isExclusive)
        val surrounder = descriptor.surrounders.first { it.templateDescription == "<%text>" }
        WriteCommandAction.runWriteCommandAction(project) {
            surrounder.surroundElements(project, myFixture.editor, elements)
        }
        assertEquals("<%text>\nkeep\n</%text>", myFixture.editor.document.text)
        assertEquals("<%text>\n".length, myFixture.editor.caretModel.offset)
    }

    fun testFormatterIndentsControlsAndKeepsText() {
        val text = """
            <div class="${'$'}{name}">
            % for item in items:
            % if item:
            ${'$'}{item}
            % endif
            % endfor
            </div>
            \
            <%text>
              keep me
            </%text>
            ## zzqxwronk
            <%
            x = 1
            %>
            """.trimIndent()
        myFixture.configureByText("page.mako", text)
        val file = myFixture.file
        CodeStyle.getIndentOptions(file).INDENT_SIZE = 4
        WriteCommandAction.runWriteCommandAction(project) {
            CodeStyleManager.getInstance(project).reformat(file)
        }
        PsiDocumentManager.getInstance(project).commitAllDocuments()
        val formatted = myFixture.editor.document.text
        val expected = """
            <div class="${'$'}{name}">
            % for item in items:
                % if item:
            ${'$'}{item}
                % endif
            % endfor
            </div>
            \
            <%text>
              keep me
            </%text>
            """.trimIndent()
        assertTrue(formatted, formatted.startsWith(expected))
        assertTrue(formatted, formatted.contains("\\\n<%text>\n  keep me\n</%text>"))
        assertFalse(formatted, formatted.contains("        % if"))
        assertTrue(formatted, formatted.contains("x = 1"))

        val expressions = PsiTreeUtil.findChildrenOfType(myFixture.file, MakoExpression::class.java)
        assertEquals(formatted, 2, expressions.size)
        assertEquals("\${name}", expressions.first { it.text.contains("name") }.text)
        assertEquals("\${item}", expressions.first { it.text.contains("item") }.text)

        val commentAt = formatted.indexOf("zzqxwronk")
        assertTrue(formatted, commentAt >= 0)
        val comment = myFixture.file.findElementAt(commentAt)
        assertNotNull(comment)
        assertSame(SpellcheckingStrategy.TEXT_TOKENIZER, MakoSpellcheckingStrategy().getTokenizer(comment!!))

        val manager = InjectedLanguageManager.getInstance(project)
        val expression = expressions.first { it.text.contains("name") }
        assertFalse(manager.getInjectedPsiFiles(expression).isNullOrEmpty())
        val python = PsiTreeUtil.findChildOfType(myFixture.file, MakoPythonBlock::class.java)
        assertNotNull(python)
        assertFalse(manager.getInjectedPsiFiles(python!!).isNullOrEmpty())
    }
}
