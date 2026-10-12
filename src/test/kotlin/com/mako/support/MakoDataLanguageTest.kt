package com.mako.support

import com.intellij.codeInspection.htmlInspections.HtmlExtraClosingTagInspection
import com.intellij.ide.highlighter.HighlighterFactory
import com.intellij.lang.annotation.HighlightSeverity
import com.intellij.lang.html.HTMLLanguage
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.fileTypes.PlainTextLanguage
import com.intellij.openapi.fileTypes.SyntaxHighlighterFactory
import com.intellij.psi.PsiManager
import com.intellij.psi.templateLanguages.TemplateDataLanguageMappings
import com.intellij.psi.xml.XmlTag
import com.intellij.psi.xml.XmlTokenType
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import com.intellij.util.FileContentUtilCore
import com.mako.support.psi.MakoExpression
import com.mako.support.template.MakoFileViewProvider

class MakoDataLanguageTest : BasePlatformTestCase() {
    private val fixtureText = "<div class=\"\${cls}\"><span></div>"

    fun testHtmlWrapsTheExpression() {
        myFixture.configureByText("page.mako", fixtureText)
        val viewProvider = myFixture.file.viewProvider as MakoFileViewProvider
        assertEquals(HTMLLanguage.INSTANCE, viewProvider.templateDataLanguage)

        val divAt = viewProvider.findElementAt(fixtureText.indexOf("div"))
        assertNotNull(divAt)
        assertEquals("div", divAt!!.text)
        // 2025.3 HTML lexes the tag name as XML_NAME. The tag composite above it is the HTML tag.
        val tag = generateSequence(divAt) { it.parent }.filterIsInstance<XmlTag>().firstOrNull()
        assertNotNull("no HTML tag above ${describe(divAt)}", tag)
        assertEquals("div", tag!!.name)

        val expressionAt = viewProvider.findElementAt(fixtureText.indexOf("cls"))
        assertTrue(expressionAt.hasAncestor<MakoExpression>())

        val html = SyntaxHighlighterFactory.getSyntaxHighlighter(HTMLLanguage.INSTANCE, project, null)
        val tagNameKeys = html.getTokenHighlights(XmlTokenType.XML_TAG_NAME).map { it.externalName }.toSet()
        val attributeNameKeys = html.getTokenHighlights(XmlTokenType.XML_NAME).map { it.externalName }.toSet()
        val distinctiveTagKeys = tagNameKeys - attributeNameKeys
        val divKeys = attributeNamesAt(fixtureText.indexOf("div"))
        assertTrue("div=$divKeys tag=$distinctiveTagKeys", divKeys.any { it in distinctiveTagKeys })
        val expressionKeys = attributeNamesAt(fixtureText.indexOf("cls"))
        assertFalse("expression=$expressionKeys", expressionKeys.any { it in tagNameKeys })
    }

    fun testHtmlCompletionOffersDivOutsideTheExpression() {
        myFixture.configureByText("page.mako", "<di<caret> class=\"\${cls}\">")
        val items = myFixture.completeBasic()
        assertNotNull(items)
        assertTrue(items.any { it.lookupString == "div" })
    }

    fun testHtmlCompletionDoesNotFireInsideTheExpression() {
        myFixture.configureByText("page.mako", "<div class=\"\${cl<caret>s}\">")
        val items = myFixture.completeBasic()
        val lookups = items?.map { it.lookupString }.orEmpty()
        assertFalse(lookups.contains("div"))
    }

    fun testUnmatchedHtmlTagIsStillReported() {
        myFixture.enableInspections(HtmlExtraClosingTagInspection::class.java)
        myFixture.configureByText("page.mako", fixtureText)
        val infos = myFixture.doHighlighting(HighlightSeverity.WARNING)
        assertTrue(
            infos.joinToString("\n") { "${it.severity}: ${it.description}" },
            infos.any { info ->
                val description = info.description ?: ""
                description.contains("tag", ignoreCase = true) ||
                    description.contains("closed", ignoreCase = true) ||
                    description.contains("element", ignoreCase = true)
            },
        )
    }

    fun testTypingGtClosesTheHtmlTag() {
        myFixture.configureByText("page.mako", "<div<caret> class=\"\${cls}\"")
        myFixture.type(">")
        val text = myFixture.editor.document.text
        assertTrue(text, text.contains("</div>"))
    }

    fun testPlainTextDataLanguageSkipsHtml() {
        myFixture.configureByText("page.mako", "<div></div>")
        val virtualFile = myFixture.file.virtualFile
        WriteCommandAction.runWriteCommandAction(project) {
            TemplateDataLanguageMappings.getInstance(project).setMapping(virtualFile, PlainTextLanguage.INSTANCE)
        }
        FileContentUtilCore.reparseFiles(virtualFile)

        val file = PsiManager.getInstance(project).findFile(virtualFile)!!
        val viewProvider = file.viewProvider as MakoFileViewProvider
        assertEquals(PlainTextLanguage.INSTANCE, viewProvider.templateDataLanguage)
        assertNull(viewProvider.getPsi(HTMLLanguage.INSTANCE))

        val htmlTagKeys = SyntaxHighlighterFactory.getSyntaxHighlighter(HTMLLanguage.INSTANCE, project, null)
            .getTokenHighlights(XmlTokenType.XML_TAG_NAME)
            .map { it.externalName }
            .toSet()
        val keys = attributeNamesAt(file.text.indexOf("div"), virtualFile, file.text)
        assertFalse("plain=$keys", keys.any { it in htmlTagKeys })

        myFixture.enableInspections(HtmlExtraClosingTagInspection::class.java)
        val infos = myFixture.doHighlighting(HighlightSeverity.WARNING)
        assertFalse(
            infos.joinToString("\n") { "${it.severity}: ${it.description}" },
            infos.any { (it.description ?: "").contains("tag", ignoreCase = true) },
        )
    }

    private fun attributeNamesAt(offset: Int): List<String> {
        return attributeNamesAt(offset, myFixture.file.virtualFile, myFixture.editor.document.text)
    }

    private fun attributeNamesAt(offset: Int, file: com.intellij.openapi.vfs.VirtualFile, text: String): List<String> {
        val highlighter = HighlighterFactory.createHighlighter(project, file)
        highlighter.setText(text)
        return highlighter.createIterator(offset).textAttributesKeys.map { it.externalName }
    }

    private fun describe(element: com.intellij.psi.PsiElement): String {
        return generateSequence(element) { it.parent }
            .take(8)
            .joinToString(" > ") { node ->
                val type = node.node?.elementType?.toString() ?: node.javaClass.simpleName
                "$type:${node.text.take(24)}"
            }
    }

    private inline fun <reified T> com.intellij.psi.PsiElement?.hasAncestor(): Boolean {
        var current = this
        while (current != null) {
            if (current is T) return true
            current = current.parent
        }
        return false
    }
}
