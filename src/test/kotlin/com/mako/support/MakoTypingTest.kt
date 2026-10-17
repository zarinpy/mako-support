package com.mako.support

import com.intellij.psi.impl.source.tree.injected.InjectedLanguageEditorUtil

class MakoTypingTest : MakoPlatformTestCase() {
    fun testGreaterThanInsertsCloser() {
        assertTyped("<%def name=\"a\"<caret>", ">", "<%def name=\"a\"></%def>", "<%def name=\"a\">".length)
        assertTyped("<%ns:header<caret>", ">", "<%ns:header></%ns:header>", "<%ns:header>".length)
        assertTyped("<%doc<caret>", ">", "<%doc></%doc>", "<%doc>".length)
    }

    fun testGreaterThanLeavesSelfClosingAndBodylessTags() {
        assertTyped("<%def name=\"a\"/<caret>", ">", "<%def name=\"a\"/>", "<%def name=\"a\"/>".length)
        assertTyped("<%page<caret>", ">", "<%page>", "<%page>".length)
        assertTyped("<div<caret>", ">", "<div></div>", "<div>".length)
    }

    fun testPercentOpensControlCompletion() {
        myFixture.configureByText("page.mako", "<caret>")
        myFixture.type("%")
        val names = myFixture.completeBasic()?.map { it.lookupString }.orEmpty()
        assertTrue(names.toString(), names.contains("if"))
        assertTrue(names.toString(), names.contains("for"))
    }

    fun testTypedControlSequenceKeepsLaterCharacters() {
        myFixture.configureByText("page.mako", "<caret>")
        myFixture.type("% if True:")
        assertEquals(
            "% if True:\n    \n% endif",
            InjectedLanguageEditorUtil.getTopLevelEditor(myFixture.editor).document.text,
        )
    }

    fun testColonInsertsMissingCloser() {
        assertTyped("% if True<caret>", ":", "% if True:\n    \n% endif", "% if True:\n    ".length)
        assertTyped("% if True<caret>\n% endif", ":", "% if True:\n    \n% endif", "% if True:\n    ".length)
        assertTyped("%if True<caret>", ":", "%if True:\n    \n%endif", "%if True:\n    ".length)
    }

    fun testEnterIndentsControlBody() {
        assertTyped("% if True:\n<caret>\n% endif", "\n", "% if True:\n\n    \n% endif", "% if True:\n\n    ".length)
    }

    fun testEnterInTextAndDocStaysAtColumnZero() {
        assertTyped("<%text>\n<caret>\n</%text>", "\n", "<%text>\n\n\n</%text>", "<%text>\n\n".length)
        assertTyped("<%doc>\n<caret>\n</%doc>", "\n", "<%doc>\n\n\n</%doc>", "<%doc>\n\n".length)
    }

    fun testEnterInPythonBlockIndents() {
        myFixture.configureByText("page.mako", "<%\nif True:<caret>\n%>")
        myFixture.type('\n')
        val text = InjectedLanguageEditorUtil.getTopLevelEditor(myFixture.editor).document.text
        assertTrue(text, text.contains("if True:\n    "))
    }

    private fun assertTyped(text: String, typed: String, expected: String, caret: Int) {
        myFixture.configureByText("page.mako", text)
        myFixture.type(typed)
        val editor = InjectedLanguageEditorUtil.getTopLevelEditor(myFixture.editor)
        assertEquals(expected, editor.document.text)
        assertEquals(expected, caret, editor.caretModel.offset)
    }
}
