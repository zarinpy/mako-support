package com.mako.support

import com.intellij.codeInsight.lookup.Lookup
import com.intellij.codeInsight.lookup.impl.LookupImpl
import com.intellij.openapi.application.WriteAction
import com.intellij.openapi.projectRoots.ProjectJdkTable
import com.intellij.openapi.projectRoots.Sdk
import com.intellij.openapi.roots.ModuleRootModificationUtil
import com.intellij.openapi.roots.OrderRootType
import com.intellij.openapi.roots.ProjectRootManager
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.psi.impl.source.tree.injected.InjectedLanguageEditorUtil
import com.jetbrains.python.sdk.PythonSdkAdditionalData
import com.jetbrains.python.sdk.PythonSdkType
import com.jetbrains.python.sdk.flavors.UnixPythonSdkFlavor
import java.nio.file.Files

class MakoCompletionTest : MakoPlatformTestCase() {
    fun testTagSkeletons() {
        assertChoice("<%<caret>", "page", "<%page args=\"§\"/>")
        assertChoice("<%<caret>", "include", "<%include file=\"§\"/>")
        assertChoice("<%<caret>", "inherit", "<%inherit file=\"§\"/>")
        assertChoice("<%<caret>", "namespace", "<%namespace name=\"§\" file=\"\"/>")
        assertChoice("<%<caret>", "def", "<%def name=\"§\">\n</%def>")
        assertChoice("<%<caret>", "block", "<%block name=\"§\">\n</%block>")
        assertChoice("<%<caret>", "call", "<%call expr=\"§\">\n</%call>")
        assertChoice("<%<caret>", "text", "<%text>\n§\n</%text>")
        assertChoice("<%<caret>", "doc", "<%doc>\n§\n</%doc>")
    }

    fun testPartialTagKeepsTypedCharacters() {
        assertChoice("<%pa<caret>", "page", "<%page args=\"§\"/>")
    }

    fun testNamespaceCallInsertsDefAndCloser() {
        myFixture.addFileToProject("templates/lib.mako", "<%def name=\"header()\"/>")
        val text = "<%namespace name=\"ns\" file=\"lib.mako\"/>\n<%ns:<caret>"
        assertChoice("templates/page.mako", text, "header", "<%namespace name=\"ns\" file=\"lib.mako\"/>\n<%ns:header>§")
    }

    fun testAttributeInsertsQuotedValue() {
        assertChoice("<%page <caret>/>", "args", "<%page args=\"§\"/>")
        assertChoice("<%page<caret>/>", "cached", "<%page cached=\"§\"/>")
    }

    fun testFilterOffersBuiltinsAndCallablesInScope() {
        val inside = """
            <%def name="outer()">
            <%def name="inner()">
            ${'$'}{name | <caret>}
            </%def>
            </%def>
            <%!
            def module_func():
                pass
            %>
            <%
            def early():
                pass
            %>
            """.trimIndent()
        myFixture.configureByText("page.mako", inside)
        val names = lookupNames()
        assertTrue(names.toString(), names.contains("h"))
        assertTrue(names.toString(), names.contains("decode."))
        assertTrue(names.toString(), names.contains("outer"))
        assertTrue(names.toString(), names.contains("inner"))
        assertTrue(names.toString(), names.contains("module_func"))
        assertFalse(names.toString(), names.contains("early"))

        val outside = """
            <%def name="outer()">
            <%def name="inner()">
            </%def>
            </%def>
            <%
            def early():
                pass
            %>
            ${'$'}{name | <caret>}
            """.trimIndent()
        myFixture.configureByText("page.mako", outside)
        val outerNames = lookupNames()
        assertTrue(outerNames.toString(), outerNames.contains("outer"))
        assertTrue(outerNames.toString(), outerNames.contains("early"))
        assertFalse(outerNames.toString(), outerNames.contains("inner"))
    }

    fun testCacheTypeAndBooleanValues() {
        assertChoice("<%page cache_type=\"<caret>\"/>", "memory", "<%page cache_type=\"memory§\"/>")
        assertChoice("<%def name=\"a\" cached=\"<caret>\"/>", "True", "<%def name=\"a\" cached=\"True§\"/>")
    }

    fun testFileUrisAndSkippedInterpolation() {
        myFixture.addFileToProject("templates/base.mako", "x")
        assertChoice("templates/page.mako", "<%inherit file=\"<caret>\"/>", "base.mako", "<%inherit file=\"base.mako§\"/>")
        configure("templates/other.mako", "<%inherit file=\"\${name}<caret>\"/>")
        val names = myFixture.completeBasic()?.map { it.lookupString }.orEmpty()
        assertFalse(names.toString(), names.any { it.contains("base.mako") })
    }

    fun testModuleAndImportNames() {
        val sdk = installPythonSdk()
        try {
            myFixture.addFileToProject("makomodule.py", "def sample_func():\n    pass\n")
            assertChoice(
                "<%namespace name=\"n\" module=\"makomod<caret>\"/>",
                "makomodule",
                "<%namespace name=\"n\" module=\"makomodule§\"/>",
            )
            myFixture.configureByText("page.mako", "<%namespace name=\"n\" module=\"makomodule\" import=\"<caret>\"/>")
            val names = lookupNames()
            assertTrue(names.toString(), names.contains("*"))
            assertTrue(names.toString(), names.contains("sample_func"))
        } finally {
            removePythonSdk(sdk)
        }
    }

    fun testIncludeImportOffersTargetDefs() {
        myFixture.addFileToProject("templates/lib.mako", "<%def name=\"header()\"/>")
        configure("templates/page.mako", "<%include file=\"lib.mako\" import=\"<caret>\"/>")
        val names = lookupNames()
        assertTrue(names.toString(), names.contains("*"))
        assertTrue(names.toString(), names.contains("header"))
    }

    fun testControlKeywordsSpacingAndClosers() {
        myFixture.configureByText("page.mako", "%<caret>")
        val top = lookupNames()
        assertTrue(top.toString(), top.contains("if"))
        assertTrue(top.toString(), top.contains("else"))
        assertFalse(top.toString(), top.contains("endif"))
        assertFalse(top.toString(), top.contains("enddef"))

        assertChoice("%<caret>", "if", "% if:\n    §\n% endif")
        assertChoice("%i<caret>", "if", "%if:\n    §\n%endif")
        assertChoice("%<caret>\n% endif", "if", "% if:\n    §\n% endif")

        myFixture.configureByText("page.mako", "% if True:\n%<caret>\n% endif")
        val inside = lookupNames()
        assertTrue(inside.toString(), inside.contains("endif"))
        assertTrue(inside.toString(), inside.contains("else"))
    }

    fun testConditionDoesNotStopPythonCompletion() {
        myFixture.configureByText("page.mako", "% if No<caret>")
        val items = myFixture.completeBasic()
        if (items == null) {
            val text = InjectedLanguageEditorUtil.getTopLevelEditor(myFixture.editor).document.text
            assertTrue(text, text.contains("None"))
            return
        }
        val names = items.map { it.lookupString }
        assertTrue(names.toString(), names.contains("None"))
    }

    private fun assertChoice(text: String, lookup: String, expected: String) {
        assertChoice("page.mako", text, lookup, expected)
    }

    private fun assertChoice(fileName: String, text: String, lookup: String, expected: String) {
        configure(fileName, text)
        choose(lookup)
        assertDocument(expected)
    }

    /** `configureByText` rejects a relative directory, so nested paths are copied in first. */
    private fun configure(fileName: String, text: String) {
        if ('/' !in fileName) {
            myFixture.configureByText(fileName, text)
            return
        }
        val marker = "<caret>"
        val offset = text.indexOf(marker)
        val body = if (offset >= 0) text.removeRange(offset, offset + marker.length) else text
        val existing = myFixture.findFileInTempDir(fileName)
        val virtual = if (existing == null) {
            myFixture.addFileToProject(fileName, body).virtualFile
        } else {
            myFixture.saveText(existing, body)
            existing
        }
        myFixture.configureFromExistingVirtualFile(virtual)
        if (offset >= 0) myFixture.editor.caretModel.moveToOffset(offset)
    }

    private fun choose(name: String) {
        val items = myFixture.completeBasic()
        if (items == null) return
        val match = items.firstOrNull { it.lookupString == name }
        assertNotNull("missing $name in ${items.map { it.lookupString }}", match)
        (myFixture.lookup as LookupImpl).finishLookup(Lookup.NORMAL_SELECT_CHAR, match)
    }

    private fun lookupNames(): List<String> {
        val items = myFixture.completeBasic()
        assertNotNull(myFixture.file.text, items)
        return items!!.map { it.lookupString }
    }

    private fun assertDocument(expectedWithCaret: String) {
        val caret = expectedWithCaret.indexOf('§')
        val expected = expectedWithCaret.replace("§", "")
        val editor = InjectedLanguageEditorUtil.getTopLevelEditor(myFixture.editor)
        assertEquals(expected, editor.document.text)
        if (caret >= 0) assertEquals(expectedWithCaret, caret, editor.caretModel.offset)
    }

    private fun installPythonSdk(): Sdk {
        val root = Files.createTempDirectory("mako-py-sdk")
        Files.writeString(root.resolve("os.py"), "class path:\n    sep = '/'\n")
        val home = root.resolve("python")
        Files.writeString(home, "")
        val virtualRoot = LocalFileSystem.getInstance().refreshAndFindFileByPath(root.toString())
            ?: error("Python SDK root is not visible: $root")
        lateinit var sdk: Sdk
        WriteAction.runAndWait<Throwable> {
            sdk = ProjectJdkTable.getInstance().createSdk("mako-test-python", PythonSdkType.getInstance())
            val modificator = sdk.sdkModificator
            modificator.homePath = home.toString()
            modificator.versionString = "Python 3.12.3"
            modificator.addRoot(virtualRoot, OrderRootType.CLASSES)
            modificator.sdkAdditionalData = PythonSdkAdditionalData(UnixPythonSdkFlavor.getInstance())
            modificator.commitChanges()
            sdk.putUserData(PythonSdkType.MOCK_PY_MARKER_KEY, true)
            sdk.putUserData(PythonSdkType.MOCK_PY_VERSION_KEY, "3.12")
            sdk.putUserData(PythonSdkType.MOCK_SYS_PATH_KEY, listOf(root.toString()))
            ProjectJdkTable.getInstance().addJdk(sdk)
            ModuleRootModificationUtil.setModuleSdk(module, sdk)
            ProjectRootManager.getInstance(project).projectSdk = sdk
        }
        return sdk
    }

    private fun removePythonSdk(sdk: Sdk) {
        WriteAction.runAndWait<Throwable> {
            ProjectJdkTable.getInstance().removeJdk(sdk)
        }
    }
}
