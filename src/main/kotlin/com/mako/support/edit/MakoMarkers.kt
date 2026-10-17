package com.mako.support.edit

import com.intellij.codeInsight.daemon.RelatedItemLineMarkerInfo
import com.intellij.codeInsight.daemon.RelatedItemLineMarkerProvider
import com.intellij.codeInsight.navigation.NavigationGutterIconBuilder
import com.intellij.icons.AllIcons
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import com.intellij.psi.util.CachedValueProvider
import com.intellij.psi.util.CachedValuesManager
import com.intellij.psi.util.PsiModificationTracker
import com.mako.support.lexer.MakoTokenTypes
import com.mako.support.psi.MakoTag
import javax.swing.Icon

class MakoLineMarkerProvider : RelatedItemLineMarkerProvider() {
    override fun getName(): String = "Mako relations"

    override fun collectNavigationMarkers(
        element: PsiElement,
        result: MutableCollection<in RelatedItemLineMarkerInfo<*>>,
    ) {
        if (element.node.elementType != MakoTokenTypes.TAG_KEYWORD) return
        val tag = element.parent as? MakoTag ?: return
        val file = makoPsi(element.containingFile) ?: return
        val relations = MakoRelations.of(file)
        if (element == firstKeyword(file) && relations.childFiles.isNotEmpty()) {
            result += marker(element, AllIcons.Gutter.ImplementedMethod, "Inherited by", relations.childFiles)
        }
        relations.inheritTargets[tag]?.let { target ->
            result += marker(element, AllIcons.Gutter.OverridenMethod, "Inherits from", listOf(target))
        }
        relations.includeTargets[tag]?.let { target ->
            result += marker(element, AllIcons.Gutter.ImplementingMethod, "Includes", listOf(target))
        }
        relations.namespaceTargets[tag]?.let { target ->
            result += marker(element, AllIcons.Gutter.ImplementingMethod, "Namespace", listOf(target))
        }
        val rendered = relations.renderedByParent[tag].orEmpty()
        if (rendered.isNotEmpty()) {
            result += marker(element, AllIcons.Gutter.OverridenMethod, "Rendered by parent", rendered)
        }
        val defined = relations.definedInParent[tag].orEmpty()
        if (defined.isNotEmpty()) {
            result += marker(element, AllIcons.Gutter.OverridenMethod, "Defined in parent", defined)
        }
        val overridden = relations.overriddenBy[tag].orEmpty()
        if (overridden.isNotEmpty()) {
            result += marker(element, AllIcons.Gutter.OverridingMethod, "Overridden by", overridden)
        }
    }

    private fun marker(
        element: PsiElement,
        icon: Icon,
        tooltip: String,
        targets: List<PsiElement>,
    ): RelatedItemLineMarkerInfo<PsiElement> {
        return NavigationGutterIconBuilder.create(icon)
            .setTargets(targets)
            .setTooltipText(tooltip)
            .createLineMarkerInfo(element)
    }

    private fun firstKeyword(file: PsiFile): PsiElement? {
        val first = file.firstChild ?: return null
        return first.node.findChildByType(MakoTokenTypes.TAG_KEYWORD)?.psi
    }
}

internal class MakoRelations(
    val inheritTargets: Map<PsiElement, PsiFile>,
    val includeTargets: Map<PsiElement, PsiFile>,
    val namespaceTargets: Map<PsiElement, PsiFile>,
    val childFiles: List<PsiFile>,
    val renderedByParent: Map<PsiElement, List<PsiElement>>,
    val definedInParent: Map<PsiElement, List<PsiElement>>,
    val overriddenBy: Map<PsiElement, List<PsiElement>>,
) {
    companion object {
        fun of(file: PsiFile): MakoRelations {
            return CachedValuesManager.getCachedValue(file) {
                CachedValueProvider.Result.create(build(file), PsiModificationTracker.MODIFICATION_COUNT)
            }
        }

        private fun build(file: PsiFile): MakoRelations {
            val outline = MakoOutline.of(file)
            val files = MakoTemplates.makoFiles(file)
            val inheritTargets = linkedMapOf<PsiElement, PsiFile>()
            for (tag in outline.inherits) {
                val target = resolve(file, tag.value("file")) ?: continue
                inheritTargets[tag.element] = target
            }
            val includeTargets = linkedMapOf<PsiElement, PsiFile>()
            for (tag in outline.includes) {
                val target = resolve(file, tag.value("file")) ?: continue
                includeTargets[tag.element] = target
            }
            val namespaceTargets = linkedMapOf<PsiElement, PsiFile>()
            for (tag in outline.namespaces) {
                val target = resolve(file, tag.value("file")) ?: continue
                namespaceTargets[tag.element] = target
            }
            val childFiles = files.filter { other ->
                other != file && MakoOutline.of(other).inherits.any { tag ->
                    sameFile(resolve(other, tag.value("file")), file)
                }
            }
            val rendered = linkedMapOf<PsiElement, MutableList<PsiElement>>()
            val defined = linkedMapOf<PsiElement, MutableList<PsiElement>>()
            val parents = inheritTargets.values.distinct()
            for (block in outline.namedBlocks) {
                for (parent in parents) {
                    val match = MakoOutline.of(parent).namedBlocks.firstOrNull { it.name == block.name } ?: continue
                    rendered.getOrPut(block.element) { mutableListOf() }.add(match.element)
                }
            }
            for (def in outline.topLevelDefs) {
                for (parent in parents) {
                    val match = MakoOutline.of(parent).topLevelDefs.firstOrNull { it.name == def.name } ?: continue
                    defined.getOrPut(def.element) { mutableListOf() }.add(match.element)
                }
            }
            val overridden = linkedMapOf<PsiElement, MutableList<PsiElement>>()
            for (block in outline.namedBlocks) {
                for (child in childFiles) {
                    val match = MakoOutline.of(child).namedBlocks.firstOrNull { it.name == block.name } ?: continue
                    overridden.getOrPut(block.element) { mutableListOf() }.add(match.element)
                }
            }
            return MakoRelations(inheritTargets, includeTargets, namespaceTargets, childFiles, rendered, defined, overridden)
        }

        private fun resolve(file: PsiFile, uri: String?): PsiFile? {
            if (uri.isNullOrBlank() || uri.contains("\${")) return null
            return MakoTemplates.resolve(uri, file)
        }

        private fun sameFile(left: PsiFile?, right: PsiFile): Boolean {
            if (left == null) return false
            if (left == right) return true
            val leftFile = left.virtualFile ?: return false
            val rightFile = right.virtualFile ?: return false
            return leftFile.path == rightFile.path
        }
    }
}
