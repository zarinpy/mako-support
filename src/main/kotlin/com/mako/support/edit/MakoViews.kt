package com.mako.support.edit

import com.intellij.ide.structureView.StructureViewBuilder
import com.intellij.ide.structureView.StructureViewModel
import com.intellij.ide.structureView.StructureViewTreeElement
import com.intellij.ide.structureView.TextEditorBasedStructureViewModel
import com.intellij.ide.structureView.TreeBasedStructureViewBuilder
import com.intellij.ide.util.treeView.smartTree.Sorter
import com.intellij.ide.util.treeView.smartTree.TreeElement
import com.intellij.lang.ASTNode
import com.intellij.lang.Language
import com.intellij.lang.PsiStructureViewFactory
import com.intellij.lang.folding.FoldingBuilder
import com.intellij.lang.folding.FoldingDescriptor
import com.intellij.navigation.ItemPresentation
import com.intellij.openapi.editor.Document
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.util.TextRange
import com.intellij.pom.Navigatable
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import com.intellij.ui.breadcrumbs.BreadcrumbsProvider
import com.mako.support.lang.MakoLanguage
import javax.swing.Icon

class MakoFoldingBuilder : FoldingBuilder {
    override fun buildFoldRegions(node: ASTNode, document: Document): Array<FoldingDescriptor> {
        val file = node.psi as? PsiFile ?: return FoldingDescriptor.EMPTY_ARRAY
        if (file.language != MakoLanguage) return FoldingDescriptor.EMPTY_ARRAY
        val outline = MakoOutline.of(file)
        val text = file.text
        val regions = mutableListOf<FoldingDescriptor>()
        for (region in outline.regions) {
            if (!foldable(region)) continue
            if (region.rangeEnd <= region.rangeStart) continue
            val range = TextRange(region.rangeStart, region.rangeEnd)
            if (!node.textRange.contains(range)) continue
            val body = text.substring(range.startOffset, range.endOffset)
            val newlines = body.count { it == '\n' }
            val minimum = if (region.kind == RegionKind.CONTROL) 2 else 1
            if (newlines < minimum) continue
            regions += FoldingDescriptor(node, range, null, placeholder(region, outline))
        }
        return regions.toTypedArray()
    }

    override fun getPlaceholderText(node: ASTNode): String = ""

    override fun isCollapsedByDefault(node: ASTNode): Boolean = false

    private fun foldable(region: Region): Boolean = when (region.kind) {
        RegionKind.DEF, RegionKind.BLOCK, RegionKind.NAMESPACE, RegionKind.PYTHON, RegionKind.DOC, RegionKind.CONTROL -> true
        else -> false
    }

    private fun placeholder(region: Region, outline: MakoOutline): String {
        if (region.kind == RegionKind.CONTROL) {
            val condition = outline.controls.firstOrNull { it.element == region.anchor }?.condition.orEmpty()
            val text = if (condition.isEmpty()) region.keyword else "${region.keyword} $condition"
            return trimmedPlaceholder(text)
        }
        val name = region.label.trim()
        val text = if (name.isEmpty() || name == region.keyword) region.keyword else "${region.keyword} $name"
        return trimmedPlaceholder(text)
    }
}

class MakoStructureViewFactory : PsiStructureViewFactory {
    override fun getStructureViewBuilder(psiFile: PsiFile): StructureViewBuilder {
        return object : TreeBasedStructureViewBuilder() {
            override fun createStructureViewModel(editor: Editor?): StructureViewModel = MakoStructureViewModel(psiFile)
        }
    }
}

private class MakoStructureViewModel(file: PsiFile) : TextEditorBasedStructureViewModel(file) {
    private val root = structureRoot(file)

    override fun getRoot(): StructureViewTreeElement = root

    override fun getSorters(): Array<Sorter> = Sorter.EMPTY_ARRAY
}

private fun structureRoot(file: PsiFile): MakoStructureElement {
    val outline = MakoOutline.of(file)
    val children = mutableListOf<MakoStructureElement>()
    for (inherit in outline.inherits) {
        children += MakoStructureElement(inherit.element, inherit.value("file") ?: "inherit", emptyList())
    }
    for (include in outline.includes) {
        children += MakoStructureElement(include.element, include.value("file") ?: "include", emptyList())
    }
    for (namespace in outline.namespaces) {
        val name = namespace.value("name").orEmpty().ifEmpty { "namespace" }
        children += MakoStructureElement(namespace.element, name, emptyList())
    }
    outline.page?.let { page ->
        children += MakoStructureElement(page.element, page.value("args") ?: "page", emptyList())
    }
    for (def in outline.topLevelDefs) children += defElement(def)
    for (block in outline.namedBlocks) {
        children += MakoStructureElement(block.element, block.name, emptyList())
    }
    return MakoStructureElement(file, file.name, children)
}

private fun defElement(def: DefFact): MakoStructureElement {
    return MakoStructureElement(def.element, def.signature, def.nested.map { defElement(it) })
}

private class MakoStructureElement(
    private val element: PsiElement,
    private val text: String,
    private val kids: List<MakoStructureElement>,
) : StructureViewTreeElement, ItemPresentation {
    override fun getValue(): Any = element

    override fun getPresentation(): ItemPresentation = this

    override fun getChildren(): Array<TreeElement> = kids.toTypedArray()

    override fun getPresentableText(): String = text

    override fun getIcon(unused: Boolean): Icon? = null

    override fun navigate(requestFocus: Boolean) {
        (element as? Navigatable)?.navigate(requestFocus)
    }

    override fun canNavigate(): Boolean = (element as? Navigatable)?.canNavigate() == true

    override fun canNavigateToSource(): Boolean = (element as? Navigatable)?.canNavigateToSource() == true
}

class MakoBreadcrumbsProvider : BreadcrumbsProvider {
    override fun getLanguages(): Array<Language> = arrayOf(MakoLanguage)

    override fun acceptElement(element: PsiElement): Boolean = crumb(element) != null

    override fun getElementInfo(element: PsiElement): String {
        val region = crumb(element) ?: return ""
        return when (region.kind) {
            RegionKind.DEF -> region.label.ifEmpty { region.keyword }
            RegionKind.CONTROL -> region.keyword
            RegionKind.BLOCK, RegionKind.NAMESPACE -> region.label.ifEmpty { region.keyword }
            else -> if (region.label.isEmpty()) region.keyword else "${region.keyword} ${region.label}"
        }
    }

    override fun getParent(element: PsiElement): PsiElement? {
        val file = makoPsi(element.containingFile) ?: return null
        val outline = MakoOutline.of(file)
        val anchored = outline.regionAnchored(element)
        if (anchored != null) {
            val containers = outline.regionsContaining(anchored.rangeStart)
                .filter { it.anchor != element && it.rangeEnd >= anchored.rangeEnd }
            return containers.lastOrNull()?.anchor
        }
        return outline.regionsContaining(element.textRange.startOffset).lastOrNull()?.anchor
    }

    private fun crumb(element: PsiElement): Region? {
        val file = makoPsi(element.containingFile) ?: return null
        val region = MakoOutline.of(file).regionAnchored(element) ?: return null
        return region.takeIf {
            it.kind == RegionKind.DEF ||
                it.kind == RegionKind.BLOCK ||
                it.kind == RegionKind.NAMESPACE ||
                it.kind == RegionKind.CALL ||
                it.kind == RegionKind.TEXT ||
                it.kind == RegionKind.DOC ||
                it.kind == RegionKind.CONTROL
        }
    }
}
