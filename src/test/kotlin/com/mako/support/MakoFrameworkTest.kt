package com.mako.support

import com.intellij.execution.filters.ConsoleFilterProvider
import com.intellij.execution.filters.OpenFileHyperlinkInfo
import com.intellij.lang.html.HTMLLanguage
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.lang.injection.InjectedLanguageManager
import com.intellij.openapi.actionSystem.ActionManager
import com.intellij.openapi.application.WriteAction
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.PsiManager
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.template.lang.core.templateLanguages.TemplateContextProvider
import com.intellij.template.lang.core.templateLanguages.TemplatesService
import com.intellij.util.FileContentUtilCore
import com.intellij.util.xmlb.XmlSerializerUtil
import com.jetbrains.python.psi.PyReferenceExpression
import com.mako.support.psi.MakoExpression
import com.mako.support.psi.MakoFile
import com.mako.support.run.MakoFramework
import com.mako.support.run.MakoProjectSettings
import com.mako.support.run.MakoPythonTemplateLanguage
import com.mako.support.run.MakoTracebackFilter
import com.mako.support.run.MakoTracebackFilterProvider
import com.mako.support.run.previewTarget

class MakoFrameworkTest : MakoPlatformTestCase() {
    override fun setUp() {
        super.setUp()
        assertFalse(
            "Python template bridge did not load",
            TemplateContextProvider.EP_NAME.extensionList.none { it is MakoFramework },
        )
    }

    fun testFlaskStyleRenderExposesKeyword() {
        myFixture.addFileToProject(
            "views.py",
            "def view():\n    render_template(\"page.mako\", name=user)\n",
        )
        myFixture.configureByText("page.mako", "\${name}")
        assertResolves("\${name}", "name")
    }

    fun testGetTemplateRenderAndGetDefScope() {
        myFixture.addFileToProject(
            "app.py",
            """
            lookup = object()
            lookup.get_template("page.mako").render(title="Hello")
            lookup.get_template("page.mako").get_def("row").render(item=1)
            template = Template(filename="page.mako")
            template.render(via_template=1)
            ctx = {"extra": 1}
            render("page.mako", **ctx)
            """.trimIndent() + "\n",
        )
        myFixture.configureByText(
            "page.mako",
            "\${title}\n\${extra}\n\${via_template}\n<%def name=\"row()\">\n\${item}\n</%def>\n\${item}\n",
        )
        assertResolves("\${title}", "title")
        assertResolves("\${extra}", "extra")
        assertResolves("\${via_template}", "via_template")
        assertResolves("\${item}", "item", occurrence = 0)
        val outside = fragment("\${item}", occurrence = 1)
        assertFalse(outside, outside.contains("item = UNDEFINED"))
    }

    fun testNonLiteralTemplateAddsNothing() {
        myFixture.addFileToProject(
            "app.py",
            "name = \"page.mako\"\nlookup.get_template(name).render(title=\"x\")\n",
        )
        myFixture.configureByText("page.mako", "\${title}")
        val text = fragment("\${title}")
        assertFalse(text, text.contains("title = UNDEFINED"))
    }

    fun testHtmlInTemplateFolderIsMako() {
        val page = myFixture.addFileToProject("templates/page.html", "\${name}\n")
        val outside = myFixture.addFileToProject("outside.html", "\${name}\n")
        myFixture.addFileToProject(
            "views.py",
            "def view():\n    render_template(\"page.html\", name=user)\n",
        )
        val folder = page.virtualFile.parent
        WriteAction.runAndWait<Throwable> {
            val service = TemplatesService.getInstance(module)
            service.setTemplateLanguage(MakoPythonTemplateLanguage)
            service.setTemplateFolders(folder)
            FileContentUtilCore.reparseFiles(page.virtualFile, outside.virtualFile)
        }
        myFixture.configureFromExistingVirtualFile(page.virtualFile)
        assertTrue(myFixture.file.text, myFixture.file is MakoFile)
        assertEquals("Mako", MakoPythonTemplateLanguage.templateLanguageName)
        assertResolves("\${name}", "name")

        val outsidePsi = PsiManager.getInstance(project).findFile(outside.virtualFile)
        assertNotNull(outsidePsi)
        assertEquals(HTMLLanguage.INSTANCE, outsidePsi!!.language)
    }

    fun testCompileExceptionAndTracebackOpenTheTemplate() {
        val file = myFixture.configureByText("page.mako", "hello\nworld\n")
        val path = file.virtualFile.path
        val filter = MakoTracebackFilter(project)

        val traceback = "  File \"$path\", line 2, in render_body"
        assertLandsOn(link(filter.applyFilter(traceback, traceback.length), traceback), file.virtualFile, 1, 0)

        val compile = "CompileException: huh in file '$path' at line: 2 char: 4"
        assertLandsOn(link(filter.applyFilter(compile, compile.length), compile), file.virtualFile, 1, 3)

        val python = "File \"/tmp/foo.py\", line 3, in render"
        assertNull(filter.applyFilter(python, python.length))

        assertNotNull(ActionManager.getInstance().getAction("Mako.Preview"))
        assertTrue(
            ConsoleFilterProvider.FILTER_PROVIDERS.extensionList.any { it is MakoTracebackFilterProvider },
        )
    }

    fun testPreviewTargetUsesTemplateRoot() {
        val file = myFixture.addFileToProject("templates/page.mako", "hi")
        val target = previewTarget(file)
        assertNotNull(target)
        assertEquals("page.mako", target!!.uri)
        assertTrue(
            target.directories.joinToString(",") { it.path },
            target.directories.any { it.name == "templates" },
        )
        val settings = MakoProjectSettings.getInstance(project)
        settings.outputEncoding = "utf-8"
        val copy = XmlSerializerUtil.createCopy(settings.state)
        assertEquals("utf-8", copy.outputEncoding)
        settings.loadState(copy)
        assertEquals("utf-8", settings.outputEncoding)
    }

    private fun assertLandsOn(info: OpenFileHyperlinkInfo, file: VirtualFile, line: Int, column: Int) {
        assertEquals(file, info.virtualFile)
        val document = FileDocumentManager.getInstance().getDocument(file)
        assertNotNull(document)
        val descriptor = info.descriptor
        assertNotNull(descriptor)
        assertEquals(document!!.getLineStartOffset(line) + column, descriptor!!.offset)
    }

    private fun link(result: com.intellij.execution.filters.Filter.Result?, line: String): OpenFileHyperlinkInfo {
        assertNotNull(line, result)
        val info = result!!.resultItems.firstOrNull()?.hyperlinkInfo
        assertTrue(line, info is OpenFileHyperlinkInfo)
        return info as OpenFileHyperlinkInfo
    }

    private fun assertResolves(expressionText: String, name: String, occurrence: Int = 0) {
        val text = fragment(expressionText, occurrence)
        assertTrue(text, text.contains("$name = UNDEFINED"))
        val ref = reference(expressionText, name, occurrence)
        assertNotNull(ref.containingFile.text, ref.reference.resolve())
    }

    private fun reference(expressionText: String, name: String, occurrence: Int): PyReferenceExpression {
        val host = expressionHost(expressionText, occurrence)
        val injected = InjectedLanguageManager.getInstance(project).getInjectedPsiFiles(host)
        assertNotNull(host.text, injected)
        val psi = injected!!.first().first
        val ref = PsiTreeUtil.findChildrenOfType(psi, PyReferenceExpression::class.java).firstOrNull { it.name == name }
        assertNotNull(psi.text, ref)
        return ref!!
    }

    private fun fragment(expressionText: String, occurrence: Int = 0): String {
        val host = expressionHost(expressionText, occurrence)
        val injected = InjectedLanguageManager.getInstance(project).getInjectedPsiFiles(host)
        assertNotNull(host.text, injected)
        return injected!!.joinToString("\n") { it.first.text }
    }

    private fun expressionHost(expressionText: String, occurrence: Int): MakoExpression {
        val file = InjectedLanguageManager.getInstance(project).getTopLevelFile(myFixture.file)
        val hosts = PsiTreeUtil.findChildrenOfType(file, MakoExpression::class.java).filter { it.text == expressionText }
        assertTrue("missing $expressionText#$occurrence in ${file.text}", hosts.size > occurrence)
        return hosts[occurrence]
    }
}
