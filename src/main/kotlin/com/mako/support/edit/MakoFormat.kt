package com.mako.support.edit

import com.intellij.application.options.CodeStyle
import com.intellij.formatting.Block
import com.intellij.formatting.ChildAttributes
import com.intellij.formatting.FormattingModel
import com.intellij.formatting.FormattingModelBuilder
import com.intellij.formatting.FormattingModelProvider
import com.intellij.formatting.Indent
import com.intellij.formatting.Spacing
import com.intellij.lang.ASTNode
import com.intellij.formatting.FormattingContext
import com.intellij.openapi.util.TextRange
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import com.intellij.psi.codeStyle.CodeStyleSettings
import com.intellij.psi.formatter.common.AbstractBlock
import com.intellij.psi.formatter.common.DefaultInjectedLanguageBlockBuilder
import com.mako.support.psi.MakoCodingComment
import com.mako.support.psi.MakoControlStatement
import com.mako.support.psi.MakoDocComment
import com.mako.support.psi.MakoLineComment
import com.mako.support.psi.MakoModuleBlock
import com.mako.support.psi.MakoPythonBlock
import com.mako.support.psi.MakoTemplateText

class MakoFormattingModelBuilder : FormattingModelBuilder {
    override fun createModel(formattingContext: FormattingContext): FormattingModel {
        val file = formattingContext.containingFile
        val settings = formattingContext.codeStyleSettings
        val block = MakoFileBlock(file.node, settings)
        return FormattingModelProvider.createFormattingModelForPsiFile(file, block, settings)
    }
}

private enum class BlockKind { CONTROL, RAW, TEXT }

private enum class GapMode { NONE, INDENT_ONLY, LINE, SPACES }

private class MakoFileBlock(
    node: ASTNode,
    private val settings: CodeStyleSettings,
) : AbstractBlock(node, null, null) {
    override fun buildChildren(): List<Block> {
        val file = node.psi as? PsiFile ?: return emptyList()
        val outline = MakoOutline.of(file)
        val options = CodeStyle.getIndentOptions(file)
        val indentSize = options.INDENT_SIZE
        val tabSize = options.TAB_SIZE
        val text = file.text
        val builder = DefaultInjectedLanguageBlockBuilder(settings)
        val result = mutableListOf<Block>()
        for (child in node.getChildren(null)) {
            val psi = child.psi
            val range = child.textRange
            if (psi is MakoTemplateText && !isRaw(psi, outline) && child.text.isBlank()) continue
            when (psi) {
                is MakoControlStatement -> {
                    val percent = child.text.indexOf('%')
                    if (percent < 0) {
                        result += rangeBlock(range, BlockKind.TEXT, Indent.getAbsoluteNoneIndent(), 0, GapMode.NONE)
                        continue
                    }
                    val start = range.startOffset + percent
                    val fact = outline.controls.firstOrNull { it.element == psi }
                    val spaces = if (fact != null) outline.controlSpaces(fact, indentSize, tabSize) else 0
                    val gapStart = result.lastOrNull()?.textRange?.endOffset ?: 0
                    val gap = if (gapStart <= start) text.substring(gapStart, start) else ""
                    val mode = when {
                        result.isEmpty() -> GapMode.INDENT_ONLY
                        gap.contains('\n') -> GapMode.LINE
                        else -> GapMode.SPACES
                    }
                    val indent = if (mode == GapMode.SPACES) Indent.getAbsoluteNoneIndent() else Indent.getSpaceIndent(spaces)
                    result += rangeBlock(TextRange(start, range.endOffset), BlockKind.CONTROL, indent, spaces, mode)
                }
                is MakoPythonBlock, is MakoModuleBlock -> {
                    val injected = mutableListOf<Block>()
                    val added = builder.addInjectedBlocks(injected, child, null, null, Indent.getAbsoluteNoneIndent())
                    if (added && covers(range, injected, text)) result += injected
                    else result += rangeBlock(range, BlockKind.TEXT, Indent.getAbsoluteNoneIndent(), 0, GapMode.NONE)
                }
                else -> {
                    if (child.text.isEmpty()) continue
                    val kind = if (isRaw(psi, outline)) BlockKind.RAW else BlockKind.TEXT
                    result += rangeBlock(range, kind, Indent.getAbsoluteNoneIndent(), 0, GapMode.NONE)
                }
            }
        }
        return result
    }

    override fun getSpacing(child1: Block?, child2: Block): Spacing? {
        val second = child2 as? MakoRangeBlock ?: return null
        val first = child1 as? MakoRangeBlock
        if (second.kind == BlockKind.RAW) return Spacing.getReadOnlySpacing()
        if (first?.kind == BlockKind.RAW && second.kind != BlockKind.CONTROL) return Spacing.getReadOnlySpacing()
        if (second.kind != BlockKind.CONTROL) return null
        return when (second.mode) {
            GapMode.LINE -> Spacing.createSpacing(0, 0, 1, false, 0)
            GapMode.SPACES -> Spacing.createSpacing(second.spaces, second.spaces, 0, false, 0)
            GapMode.INDENT_ONLY, GapMode.NONE -> null
        }
    }

    override fun isLeaf(): Boolean = false

    override fun getChildAttributes(newChildIndex: Int): ChildAttributes {
        return ChildAttributes(Indent.getNoneIndent(), null)
    }

    private fun isRaw(psi: PsiElement?, outline: MakoOutline): Boolean {
        if (psi is MakoLineComment || psi is MakoCodingComment || psi is MakoDocComment) return true
        if (psi is MakoTemplateText) {
            val range = psi.textRange
            return outline.regions.any {
                it.kind == RegionKind.TEXT && range.startOffset >= it.bodyStart && range.endOffset <= it.bodyEnd
            }
        }
        return false
    }

    private fun covers(range: TextRange, blocks: List<Block>, text: String): Boolean {
        if (blocks.isEmpty()) return false
        val sorted = blocks.sortedBy { it.textRange.startOffset }
        if (!sorted.all { range.contains(it.textRange) }) return false
        var cursor = range.startOffset
        for (block in sorted) {
            val start = block.textRange.startOffset
            if (start < cursor || !text.subSequence(cursor, start).all { it.isWhitespace() }) return false
            cursor = block.textRange.endOffset
        }
        return cursor <= range.endOffset && text.subSequence(cursor, range.endOffset).all { it.isWhitespace() }
    }
}

private class MakoRangeBlock(
    private val range: TextRange,
    private val indent: Indent,
    val kind: BlockKind,
    val spaces: Int,
    val mode: GapMode,
) : Block {
    override fun getTextRange(): TextRange = range

    override fun getSubBlocks(): List<Block> = emptyList()

    override fun getWrap() = null

    override fun getIndent(): Indent = indent

    override fun getAlignment() = null

    override fun getSpacing(child1: Block?, child2: Block): Spacing? = null

    override fun getChildAttributes(newChildIndex: Int): ChildAttributes = ChildAttributes(Indent.getNoneIndent(), null)

    override fun isIncomplete(): Boolean = false

    override fun isLeaf(): Boolean = true
}

private fun rangeBlock(range: TextRange, kind: BlockKind, indent: Indent, spaces: Int, mode: GapMode): MakoRangeBlock {
    return MakoRangeBlock(range, indent, kind, spaces, mode)
}
