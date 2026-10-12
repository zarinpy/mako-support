package com.mako.support

import com.intellij.lang.injection.InjectedLanguageManager
import com.intellij.openapi.application.WriteAction
import com.intellij.openapi.fileTypes.SyntaxHighlighterFactory
import com.intellij.openapi.projectRoots.ProjectJdkTable
import com.intellij.openapi.projectRoots.Sdk
import com.intellij.openapi.roots.ModuleRootModificationUtil
import com.intellij.openapi.roots.OrderRootType
import com.intellij.openapi.roots.ProjectRootManager
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiErrorElement
import com.intellij.psi.util.PsiTreeUtil
import com.jetbrains.python.PythonLanguage
import com.jetbrains.python.psi.PyFunction
import com.jetbrains.python.psi.PyReferenceExpression
import com.jetbrains.python.sdk.PythonSdkAdditionalData
import com.jetbrains.python.sdk.PythonSdkType
import com.jetbrains.python.sdk.flavors.UnixPythonSdkFlavor
import com.mako.support.psi.MakoAttribute
import com.mako.support.psi.MakoControlStatement
import com.mako.support.psi.MakoExpression
import com.mako.support.psi.MakoModuleBlock
import com.mako.support.psi.MakoPythonBlock
import java.nio.file.Files

class MakoPythonInjectionTest : MakoPlatformTestCase() {
    fun testContextWriteCompletesAndResolves() {
        myFixture.configureByText("page.mako", "\${context.write(\"x\")}")
        val write = referenceNamed("write")
        val resolved = write.reference.resolve()
        assertNotNull(write.containingFile.text, resolved)
        assertTrue(resolved is PyFunction)
        assertEquals("write", (resolved as PyFunction).name)
        assertNull(PsiTreeUtil.findChildOfType(write.containingFile, PsiErrorElement::class.java))

        myFixture.configureByText("page.mako", "\${context.<caret>write(\"x\")}")
        val items = myFixture.completeBasic()
        assertNotNull(items)
        assertTrue(items.joinToString(",") { it.lookupString }, items.any { it.lookupString == "write" })
    }

    fun testLoopMembersCompleteInsideFor() {
        myFixture.configureByText(
            "page.mako",
            "% for a in items:\n\${loop.<caret>}\n% endfor\n",
        )
        val items = myFixture.completeBasic()
        assertNotNull(items)
        val names = items.map { it.lookupString }.toSet()
        assertTrue(names.toString(), names.containsAll(setOf("index", "first", "cycle")))
    }

    fun testLoopIsAbsentWhenPageDisablesIt() {
        myFixture.configureByText(
            "page.mako",
            "<%page enable_loop=\"False\"/>\n% for a in items:\n\${loop.index}\n% endfor\n",
        )
        val loop = referenceNamed("loop")
        assertFalse(loop.containingFile.text, loop.containingFile.text.contains("loop: LoopContext"))
    }

    fun testModuleImportIsVisibleInAnExpression() {
        val sdk = installPythonSdk()
        try {
            myFixture.addFileToProject(
                "os.py",
                "class path:\n    sep = '/'\n",
            )
            myFixture.configureByText(
                "page.mako",
                "<%!\nimport os\n%>\n\${os.path.sep}\n",
            )
            val os = referenceNamed("os", occurrence = 1)
            assertNotNull(os.containingFile.text, os.reference.resolve())
        } finally {
            removePythonSdk(sdk)
        }
    }

    fun testContextIsNotDefinedInsideModuleBlock() {
        myFixture.configureByText("page.mako", "<%!\ncontext\n%>\n")
        val context = referenceNamed("context")
        assertNull(context.reference.resolve())
        assertFalse(context.containingFile.text, context.containingFile.text.contains("class Context"))
    }

    fun testTopLevelDefIsVisibleAboveItsDefinition() {
        myFixture.configureByText(
            "page.mako",
            "\${hello(\"a\")}\n<%def name=\"hello(who)\">\n\${who}\n</%def>\n",
        )
        val hello = referenceNamed("hello")
        val resolved = hello.reference.resolve()
        assertNotNull(hello.containingFile.text, resolved)
        assertEquals("hello", (resolved as PyFunction).name)

        myFixture.configureByText(
            "page.mako",
            "<%def name=\"hello(who)\">\n\${wh<caret>}\n</%def>\n",
        )
        val items = myFixture.completeBasic()
        if (items == null) {
            val top = InjectedLanguageManager.getInstance(project).getTopLevelFile(myFixture.file)
            assertTrue(top.text, top.text.contains("\${who}"))
        } else {
            assertTrue(items.joinToString(",") { it.lookupString }, items.any { it.lookupString == "who" })
        }
    }

    fun testBadDefSignatureIsAPythonSyntaxError() {
        myFixture.configureByText("page.mako", "<%def name=\"bad(1)\"/>\n")
        val attribute = PsiTreeUtil.findChildOfType(myFixture.file, MakoAttribute::class.java)
        assertNotNull(attribute)
        val injected = injectedFiles(attribute!!).single()
        assertNotNull(PsiTreeUtil.findChildOfType(injected, PsiErrorElement::class.java))
    }

    fun testAssignmentInsideDefMakesTheNameLocal() {
        myFixture.configureByText(
            "page.mako",
            "<%!\nx = 0\n%>\n<%def name=\"inner()\">\n\${x}\n<% x = 1 %>\n</%def>\n",
        )
        val name = referenceNamed("x", occurrence = 1)
        val resolved = name.reference.resolve()
        assertNotNull(name.containingFile.text, resolved)
        val function = PsiTreeUtil.getParentOfType(resolved, PyFunction::class.java)
        assertEquals("inner", function?.name)
    }

    fun testBuiltinFiltersAreNotInjected() {
        myFixture.configureByText("page.mako", "\${expr | h, trim}\n")
        val expression = PsiTreeUtil.findChildOfType(myFixture.file, MakoExpression::class.java)!!
        val text = expression.text
        assertNull(injectedAt(expression.textRange.startOffset + text.indexOf("h")))
        assertNull(injectedAt(expression.textRange.startOffset + text.indexOf("trim")))
        assertNotNull(injectedAt(expression.textRange.startOffset + text.indexOf("expr")))
    }

    fun testCustomFilterResolvesToTheModuleFunction() {
        myFixture.configureByText(
            "page.mako",
            "<%!\ndef myescape(value):\n    return value\n%>\n\${expr | myescape}\n",
        )
        val filter = referenceNamed("myescape", occurrence = 1)
        val resolved = filter.reference.resolve()
        assertNotNull(filter.containingFile.text, resolved)
        assertEquals("myescape", (resolved as PyFunction).name)
    }

    fun testEveryHostCarriesPython() {
        myFixture.configureByText(
            "page.mako",
            """
            <%page args="alpha"/>
            <%!
            import os
            %>
            <%def name="hello(who)" decorator="myescape">
            <%
            value = 1
            %>
            % if alpha:
            % endif
            </%def>
            <%call expr="hello('a')"/>
            <%block name="slot" args="beta"/>
            <div class="${'$'}{cls}"></div>
            <%include file="${'$'}{cls}.mako"/>
            ${'$'}{expr | myfilters.tagfilter}
            ${'$'}{expr | decode.utf8}
            """.trimIndent(),
        )
        val module = PsiTreeUtil.findChildOfType(myFixture.file, MakoModuleBlock::class.java)!!
        assertPython(module)
        assertPython(PsiTreeUtil.findChildOfType(myFixture.file, MakoPythonBlock::class.java)!!)
        val controls = PsiTreeUtil.findChildrenOfType(myFixture.file, MakoControlStatement::class.java)
        assertPython(only(controls, "% if") { it.text.trim().startsWith("% if") })
        val attributes = PsiTreeUtil.findChildrenOfType(myFixture.file, MakoAttribute::class.java)
        assertPython(only(attributes, "page args") { it.text.startsWith("args=\"alpha\"") })
        assertPython(only(attributes, "def name") { it.text.startsWith("name=\"hello") })
        assertPython(only(attributes, "decorator") { it.text.startsWith("decorator=") })
        assertPython(only(attributes, "call expr") { it.text.startsWith("expr=") })
        assertPython(only(attributes, "block args") { it.text.startsWith("args=\"beta\"") })
        assertPython(only(attributes, "include file") { it.text.startsWith("file=") && it.text.contains("\${cls}") })
        val expressions = PsiTreeUtil.findChildrenOfType(myFixture.file, MakoExpression::class.java)
        // `<div class="${cls}">` is template text plus an expression, not a Mako attribute.
        assertPython(only(expressions, "div expression") { it.text == "\${cls}" })
        val custom = only(expressions, "custom filter") { it.text.contains("myfilters.tagfilter") }
        assertNotNull(injectedAt(custom.textRange.startOffset + custom.text.indexOf("tagfilter")))
        val decode = only(expressions, "decode filter") { it.text.contains("decode.utf8") }
        assertNull(injectedAt(decode.textRange.startOffset + decode.text.indexOf("decode")))

        val python = injectedFiles(module).single()
        val importer = python.findElementAt(python.text.indexOf("import"))
        assertNotNull(python.text, importer)
        assertEquals(PythonLanguage.getInstance(), importer!!.language)
        val keys = SyntaxHighlighterFactory.getSyntaxHighlighter(importer.language, project, null)
            .getTokenHighlights(importer.node.elementType)
        assertTrue(keys.isNotEmpty())
    }

    private fun referenceNamed(name: String, occurrence: Int = 0): PyReferenceExpression {
        val file = InjectedLanguageManager.getInstance(project).getTopLevelFile(myFixture.file)
        val hostOffset = wordOffset(file.text, name, occurrence)
        assertTrue("missing $name#$occurrence in ${file.text}", hostOffset >= 0)
        val manager = InjectedLanguageManager.getInstance(project)
        val direct = manager.findInjectedElementAt(file, hostOffset)
        val directReference = direct?.let {
            PsiTreeUtil.getParentOfType(it, PyReferenceExpression::class.java, false)
        }
        if (directReference != null && directReference.name == name) return directReference

        val covering = mutableListOf<PyReferenceExpression>()
        val mapped = mutableListOf<PyReferenceExpression>()
        for (host in injectionHosts(file)) {
            val injected = manager.getInjectedPsiFiles(host) ?: continue
            for (place in injected) {
                val psi = place.first
                val range = place.second
                if (!range.shiftRight(host.textRange.startOffset).contains(hostOffset)) continue
                for (ref in PsiTreeUtil.findChildrenOfType(psi, PyReferenceExpression::class.java)) {
                    if (ref.name != name) continue
                    val nameNode = ref.nameElement ?: continue
                    covering.add(ref)
                    if (manager.injectedToHost(ref, nameNode.textRange.startOffset) == hostOffset) mapped.add(ref)
                }
            }
        }
        val chosen = mapped.ifEmpty { covering }
        assertFalse(
            "no Python reference $name#$occurrence at $hostOffset; saw ${covering.map { it.text }}",
            chosen.isEmpty(),
        )
        return chosen.first()
    }

    /**
     * The expression resolves `import os` only when the module has a Python SDK
     * whose class path contains `os.py`. The real interpreter is not required.
     */
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

    private fun injectionHosts(file: PsiElement): List<PsiElement> {
        return PsiTreeUtil.findChildrenOfAnyType(
            file,
            MakoExpression::class.java,
            MakoPythonBlock::class.java,
            MakoModuleBlock::class.java,
            MakoControlStatement::class.java,
            MakoAttribute::class.java,
        ).toList()
    }

    private fun wordOffset(text: String, name: String, occurrence: Int): Int {
        var searchFrom = 0
        var seen = 0
        while (searchFrom < text.length) {
            val at = text.indexOf(name, searchFrom)
            if (at < 0) return -1
            val before = if (at == 0) ' ' else text[at - 1]
            val afterIndex = at + name.length
            val after = if (afterIndex >= text.length) ' ' else text[afterIndex]
            val word = !before.isLetterOrDigit() && before != '_' && !after.isLetterOrDigit() && after != '_'
            if (word) {
                if (seen == occurrence) return at
                seen++
            }
            searchFrom = at + name.length
        }
        return -1
    }

    private fun <T : PsiElement> only(elements: Collection<T>, description: String, predicate: (T) -> Boolean): T {
        val match = elements.filter(predicate)
        assertFalse("$description not found in ${elements.map { it.text }}", match.isEmpty())
        return match.first()
    }

    private fun injectedAt(offset: Int): PsiElement? {
        return InjectedLanguageManager.getInstance(project).findInjectedElementAt(myFixture.file, offset)
    }

    private fun injectedFiles(host: PsiElement): List<PsiElement> {
        return InjectedLanguageManager.getInstance(project).getInjectedPsiFiles(host)
            ?.map { it.first } ?: emptyList()
    }

    private fun assertPython(host: PsiElement) {
        val files = injectedFiles(host)
        assertFalse(host.text, files.isEmpty())
        assertTrue(host.text, files.all { it.language == PythonLanguage.getInstance() })
    }
}
