package com.mako.support

import com.intellij.codeInsight.daemon.LineMarkerInfo
import com.intellij.codeInsight.daemon.RelatedItemLineMarkerInfo
import com.intellij.psi.PsiFile

class MakoGutterTest : MakoPlatformTestCase() {
    fun testInheritanceMarkers() {
        val base = myFixture.addFileToProject(
            "templates/base.mako",
            """
            <%block name="header">
            base
            </%block>
            <%def name="title()"/>
            """.trimIndent(),
        )
        val child = myFixture.addFileToProject(
            "templates/child.mako",
            """
            <%inherit file="base.mako"/>
            <%block name="header">
            child
            </%block>
            <%def name="title()"/>
            """.trimIndent(),
        )
        myFixture.configureFromExistingVirtualFile(child.virtualFile)
        myFixture.doHighlighting()
        val childMarks = marks()
        assertTrue(childMarks.toString(), childMarks.any { it.tooltip == "Inherits from" && it.targets.contains("base.mako") })
        assertTrue(childMarks.toString(), childMarks.any { it.tooltip == "Rendered by parent" && it.targets.contains("base.mako") })
        assertTrue(childMarks.toString(), childMarks.any { it.tooltip == "Defined in parent" && it.targets.contains("base.mako") })

        myFixture.configureFromExistingVirtualFile(base.virtualFile)
        myFixture.doHighlighting()
        val baseMarks = marks()
        assertTrue(baseMarks.toString(), baseMarks.any { it.tooltip == "Inherited by" && it.targets.contains("child.mako") })
        assertTrue(baseMarks.toString(), baseMarks.any { it.tooltip == "Overridden by" && it.targets.contains("child.mako") })
    }

    private fun marks(): List<Mark> {
        return myFixture.findAllGutters().mapNotNull { gutter ->
            val renderer = gutter as? LineMarkerInfo.LineMarkerGutterIconRenderer<*> ?: return@mapNotNull null
            val info = renderer.lineMarkerInfo as? RelatedItemLineMarkerInfo<*> ?: return@mapNotNull null
            val targets = info.createGotoRelatedItems().mapNotNull { item ->
                val element = item.element ?: return@mapNotNull null
                (element as? PsiFile)?.name ?: element.containingFile?.name
            }
            Mark(renderer.tooltipText ?: "", targets)
        }
    }

    private data class Mark(val tooltip: String, val targets: List<String>)
}
