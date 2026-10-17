package com.mako.support

import com.intellij.ide.structureView.StructureViewTreeElement
import com.intellij.lang.folding.FoldingDescriptor
import com.intellij.psi.PsiElement
import com.mako.support.edit.MakoBreadcrumbsProvider
import com.mako.support.edit.MakoFoldingBuilder
import com.mako.support.edit.MakoStructureViewFactory

class MakoOutlineViewsTest : MakoPlatformTestCase() {
    fun testFoldingDescriptors() {
        val text = """
            <%def name="hello(who)">
            body
            </%def>
            % if True:
            x
            % endif
            % if ready:
            % endif
            <%namespace name="ns" file="lib.mako">
            inner
            </%namespace>
            <%
            x = 1
            %>
            <%doc>
            note
            </%doc>
            <%block name="header"/>
            """.trimIndent()
        myFixture.configureByText("page.mako", text)
        val file = myFixture.file
        val descriptors = MakoFoldingBuilder().buildFoldRegions(file.node, myFixture.editor.document)
        val placeholders = descriptors.map { it.placeholderText }
        assertTrue(placeholders.toString(), placeholders.contains("def hello(who)"))
        assertTrue(placeholders.toString(), placeholders.contains("if True:"))
        assertFalse(placeholders.toString(), placeholders.any { it?.startsWith("if ready") == true })
        assertTrue(placeholders.toString(), placeholders.contains("namespace ns"))
        assertTrue(placeholders.toString(), placeholders.contains("<%"))
        assertTrue(placeholders.toString(), placeholders.contains("doc"))
        assertTrue(descriptors.all { it.element == file.node })
        assertTrue(descriptors.all { foldedText(file.text, it).contains("\n") })
    }

    fun testStructureViewSnapshot() {
        val text = """
            <%inherit file="base.mako"/>
            <%include file="a.mako"/>
            <%namespace name="ns" file="b.mako"/>
            <%page args="x"/>
            <%def name="outer()">
            <%def name="inner()"/>
            </%def>
            <%block name="header"/>
            """.trimIndent()
        myFixture.configureByText("page.mako", text)
        val model = MakoStructureViewFactory().getStructureViewBuilder(myFixture.file)!!
            .let { it as com.intellij.ide.structureView.TreeBasedStructureViewBuilder }
            .createStructureViewModel(myFixture.editor)
        assertTrue(model.sorters.isEmpty())
        val root = model.root
        val children = root.children.map { it as StructureViewTreeElement }
        assertEquals(
            listOf("base.mako", "a.mako", "ns", "x", "outer()", "header"),
            children.map { it.presentation.presentableText },
        )
        val outer = children.first { it.presentation.presentableText == "outer()" }
        assertEquals(listOf("inner()"), outer.children.map { (it as StructureViewTreeElement).presentation.presentableText })
        model.dispose()
    }

    fun testBreadcrumbsWalkOutward() {
        myFixture.configureByText(
            "page.mako",
            """
            <%def name="outer()">
            <%block name="header">
            % if True:
            <caret>
            % endif
            </%block>
            </%def>
            """.trimIndent(),
        )
        val provider = MakoBreadcrumbsProvider()
        val offset = myFixture.caretOffset
        var element: PsiElement? = myFixture.file.findElementAt(offset) ?: myFixture.file.findElementAt((offset - 1).coerceAtLeast(0))
        val crumbs = mutableListOf<String>()
        val seen = mutableSetOf<PsiElement>()
        while (element != null && seen.add(element)) {
            if (provider.acceptElement(element)) crumbs += provider.getElementInfo(element)
            element = provider.getParent(element)
        }
        assertEquals(listOf("if", "header", "outer()"), crumbs)
    }

    private fun foldedText(text: String, descriptor: FoldingDescriptor): String {
        val range = descriptor.range
        return text.substring(range.startOffset, range.endOffset)
    }
}
