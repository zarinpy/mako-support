package com.mako.support.edit

import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import com.intellij.psi.util.CachedValueProvider
import com.intellij.psi.util.CachedValuesManager
import com.mako.support.inject.controlKeyword
import com.mako.support.inject.parseSignature
import com.mako.support.inject.unquote
import com.mako.support.lexer.MakoTokenTypes
import com.mako.support.psi.MakoAttribute
import com.mako.support.psi.MakoClosingTag
import com.mako.support.psi.MakoControlStatement
import com.mako.support.psi.MakoDocComment
import com.mako.support.psi.MakoModuleBlock
import com.mako.support.psi.MakoPythonBlock
import com.mako.support.psi.MakoTag

internal enum class ControlRole { OPEN, CLOSE, INTERIOR, STATEMENT }

internal enum class RegionKind { DEF, BLOCK, NAMESPACE, CALL, TEXT, DOC, CONTROL, PYTHON, MODULE }

internal class AttrFact(
    val name: String,
    val value: String?,
)

internal class TagFact(
    val element: PsiElement,
    val keyword: String,
    val selfClosing: Boolean,
    val attributes: List<AttrFact>,
) {
    fun value(name: String): String? = attributes.firstOrNull { it.name == name }?.value
}

internal class ControlFact(
    val element: MakoControlStatement,
    val keyword: String,
    val condition: String,
    val role: ControlRole,
    val depth: Int,
    val baseWhitespace: String,
)

internal class Region(
    val anchor: PsiElement,
    val kind: RegionKind,
    val keyword: String,
    val label: String,
    val rangeStart: Int,
    var rangeEnd: Int,
    val bodyStart: Int,
    var bodyEnd: Int,
    val children: MutableList<Region> = mutableListOf(),
)

internal class DefFact(
    val element: PsiElement,
    val name: String,
    val signature: String,
    val nested: MutableList<DefFact> = mutableListOf(),
)

internal class MakoOutline(
    val tags: List<TagFact>,
    val controls: List<ControlFact>,
    val regions: List<Region>,
    val inherits: List<TagFact>,
    val includes: List<TagFact>,
    val namespaces: List<TagFact>,
    val page: TagFact?,
    val topLevelDefs: List<DefFact>,
    val namedBlocks: List<DefFact>,
    private val moduleCode: List<String>,
    private val pythonCode: List<Pair<Int, String>>,
) {
    fun regionAnchored(element: PsiElement): Region? = regions.firstOrNull { it.anchor == element }

    fun regionsContaining(offset: Int): List<Region> {
        return regions
            .filter { offset >= it.rangeStart && offset < it.rangeEnd }
            .sortedByDescending { it.rangeEnd - it.rangeStart }
    }

    fun unclosedOpeners(offset: Int): List<String> {
        return regions
            .filter { it.kind == RegionKind.CONTROL && it.keyword in OPENERS && offset >= it.bodyStart && offset < it.rangeEnd }
            .sortedBy { it.rangeStart }
            .map { it.keyword }
    }

    fun regionsOutward(offset: Int): List<Region> = regionsContaining(offset).asReversed()

    fun callables(offset: Int): List<String> {
        val names = linkedSetOf<String>()
        fun walk(defs: List<DefFact>, topLevel: Boolean) {
            for (def in defs) {
                if (topLevel || regionContainsDef(def, offset)) names += def.name
                walk(def.nested, false)
            }
        }
        walk(topLevelDefs, true)
        namedBlocks.forEach { names += it.name }
        moduleCode.forEach { names += functionNames(it) }
        for ((start, code) in pythonCode) {
            if (start <= offset) names += functionNames(code)
        }
        return names.filter { it.isNotEmpty() }
    }

    fun exportedNames(): List<String> {
        val names = linkedSetOf<String>()
        topLevelDefs.forEach { names += it.name }
        namedBlocks.forEach { names += it.name }
        return names.toList()
    }

    fun controlSpaces(fact: ControlFact, indentSize: Int, tabSize: Int): Int {
        return indentColumn(fact.baseWhitespace, tabSize) + fact.depth * indentSize
    }

    private fun regionContainsDef(def: DefFact, offset: Int): Boolean {
        val region = regions.firstOrNull { it.anchor == def.element } ?: return false
        return offset >= region.bodyStart && offset < region.bodyEnd
    }

    companion object {
        fun of(file: PsiFile): MakoOutline {
            return CachedValuesManager.getCachedValue(file) {
                CachedValueProvider.Result.create(build(file), file)
            }
        }

        private fun build(file: PsiFile): MakoOutline {
            val tags = mutableListOf<TagFact>()
            val controls = mutableListOf<ControlFact>()
            val regions = mutableListOf<Region>()
            val inherits = mutableListOf<TagFact>()
            val includes = mutableListOf<TagFact>()
            val namespaces = mutableListOf<TagFact>()
            var page: TagFact? = null
            val topLevelDefs = mutableListOf<DefFact>()
            val namedBlocks = mutableListOf<DefFact>()
            val moduleCode = mutableListOf<String>()
            val pythonCode = mutableListOf<Pair<Int, String>>()
            val regionStack = ArrayDeque<Region>()
            val defStack = ArrayDeque<DefFact>()
            val controlStack = ArrayDeque<String>()
            var groupBase = ""
            val text = file.text

            fun parentRegion(): Region? = regionStack.lastOrNull()

            for (child in file.children) {
                when (child) {
                    is MakoTag -> {
                        val fact = tagFact(child)
                        tags += fact
                        when (fact.keyword) {
                            "page" -> if (page == null) page = fact
                            "inherit" -> inherits += fact
                            "include" -> includes += fact
                            "namespace" -> namespaces += fact
                        }
                        val def = defFact(fact)
                        if (def != null) {
                            if (fact.keyword == "block") {
                                if (defStack.isEmpty()) namedBlocks += def
                            } else if (defStack.isEmpty()) {
                                topLevelDefs += def
                            } else {
                                defStack.last().nested += def
                            }
                        }
                        if (!fact.selfClosing && takesBody(fact.keyword)) {
                            val region = Region(
                                anchor = child,
                                kind = regionKind(fact.keyword),
                                keyword = fact.keyword,
                                label = regionLabel(fact),
                                rangeStart = child.textRange.startOffset,
                                rangeEnd = file.textLength,
                                bodyStart = child.textRange.endOffset,
                                bodyEnd = file.textLength,
                            )
                            parentRegion()?.children?.add(region)
                            regionStack.addLast(region)
                            regions += region
                            if (def != null && fact.keyword == "def") defStack.addLast(def)
                        }
                    }
                    is MakoClosingTag -> {
                        val keyword = child.node.findChildByType(MakoTokenTypes.TAG_KEYWORD)?.text ?: continue
                        val closeAt = child.textRange.startOffset
                        while (regionStack.isNotEmpty()) {
                            val frame = regionStack.removeLast()
                            frame.bodyEnd = closeAt
                            frame.rangeEnd = closeAt
                            if (frame.kind == RegionKind.CONTROL) {
                                if (controlStack.isNotEmpty()) controlStack.removeLast()
                                continue
                            }
                            if (frame.kind == RegionKind.DEF) {
                                if (defStack.isNotEmpty()) defStack.removeLast()
                            }
                            if (frame.keyword == keyword) {
                                frame.rangeEnd = child.textRange.endOffset
                                break
                            }
                        }
                    }
                    is MakoDocComment -> {
                        val body = child.node.findChildByType(MakoTokenTypes.DOC_BODY)
                        val region = Region(
                            anchor = child,
                            kind = RegionKind.DOC,
                            keyword = "doc",
                            label = "doc",
                            rangeStart = child.textRange.startOffset,
                            rangeEnd = child.textRange.endOffset,
                            bodyStart = body?.startOffset ?: child.textRange.endOffset,
                            bodyEnd = body?.let { it.startOffset + it.textLength } ?: child.textRange.endOffset,
                        )
                        parentRegion()?.children?.add(region)
                        regions += region
                    }
                    is MakoPythonBlock -> {
                        val body = child.node.findChildByType(MakoTokenTypes.PY_BODY)?.text ?: ""
                        pythonCode += child.textRange.startOffset to body
                        regions += codeRegion(child, RegionKind.PYTHON, "<%")
                    }
                    is MakoModuleBlock -> {
                        val body = child.node.findChildByType(MakoTokenTypes.PY_BODY)?.text ?: ""
                        moduleCode += body
                        regions += codeRegion(child, RegionKind.MODULE, "<%!")
                    }
                    is MakoControlStatement -> {
                        val keyword = controlKeyword(child.text) ?: continue
                        val role = controlRole(keyword)
                        val depth = when (role) {
                            ControlRole.OPEN -> controlStack.size
                            ControlRole.CLOSE, ControlRole.INTERIOR -> (controlStack.size - 1).coerceAtLeast(0)
                            ControlRole.STATEMENT -> controlStack.size
                        }
                        if (role == ControlRole.OPEN && controlStack.isEmpty()) {
                            groupBase = surroundingIndent(text, child.textRange.startOffset)
                        }
                        controls += ControlFact(child, keyword, conditionText(child.text), role, depth, groupBase)
                        when (role) {
                            ControlRole.OPEN -> {
                                val region = Region(
                                    anchor = child,
                                    kind = RegionKind.CONTROL,
                                    keyword = keyword,
                                    label = keyword,
                                    rangeStart = child.textRange.startOffset,
                                    rangeEnd = file.textLength,
                                    bodyStart = child.textRange.endOffset,
                                    bodyEnd = file.textLength,
                                )
                                parentRegion()?.children?.add(region)
                                regionStack.addLast(region)
                                regions += region
                                controlStack.addLast(keyword)
                            }
                            ControlRole.CLOSE -> {
                                val expected = keyword.removePrefix("end")
                                while (regionStack.isNotEmpty() && regionStack.last().kind == RegionKind.CONTROL) {
                                    val frame = regionStack.removeLast()
                                    frame.bodyEnd = child.textRange.startOffset
                                    frame.rangeEnd = child.textRange.startOffset
                                    if (controlStack.isNotEmpty()) controlStack.removeLast()
                                    if (frame.keyword == expected) {
                                        frame.rangeEnd = child.textRange.endOffset
                                        break
                                    }
                                }
                                if (controlStack.isEmpty()) groupBase = ""
                            }
                            else -> Unit
                        }
                    }
                }
            }
            val fileEnd = file.textLength
            while (regionStack.isNotEmpty()) {
                val frame = regionStack.removeLast()
                frame.rangeEnd = fileEnd
                frame.bodyEnd = fileEnd
            }
            return MakoOutline(
                tags, controls, regions, inherits, includes, namespaces, page,
                topLevelDefs, namedBlocks, moduleCode, pythonCode,
            )
        }

        private fun codeRegion(element: PsiElement, kind: RegionKind, keyword: String): Region {
            return Region(
                anchor = element,
                kind = kind,
                keyword = keyword,
                label = keyword,
                rangeStart = element.textRange.startOffset,
                rangeEnd = element.textRange.endOffset,
                bodyStart = element.textRange.startOffset,
                bodyEnd = element.textRange.endOffset,
            )
        }

        private fun tagFact(tag: MakoTag): TagFact {
            val keyword = tag.node.findChildByType(MakoTokenTypes.TAG_KEYWORD)?.text ?: ""
            val selfClosing = tag.node.findChildByType(MakoTokenTypes.TAG_END)?.text == "/>"
            val attributes = tag.children.filterIsInstance<MakoAttribute>().map { attribute ->
                val name = attribute.node.findChildByType(MakoTokenTypes.ATTR_NAME)?.text ?: ""
                val raw = attribute.node.findChildByType(MakoTokenTypes.ATTR_VALUE)?.text
                val value = raw?.let { unquote(it)?.substring(it) }
                AttrFact(name, value)
            }
            return TagFact(tag, keyword, selfClosing, attributes)
        }

        private fun defFact(tag: TagFact): DefFact? {
            return when (tag.keyword) {
                "def" -> {
                    val raw = tag.value("name") ?: return null
                    val signature = parseSignature(raw)
                    val name = signature?.name ?: raw.substringBefore('(').trim().ifEmpty { raw }
                    DefFact(tag.element, name, raw.trim())
                }
                "block" -> {
                    val name = tag.value("name")?.trim().orEmpty()
                    if (name.isEmpty() || name.any { !(it.isLetterOrDigit() || it == '_') }) return null
                    DefFact(tag.element, name, name)
                }
                else -> null
            }
        }

        private fun regionKind(keyword: String): RegionKind = when (keyword) {
            "def" -> RegionKind.DEF
            "block" -> RegionKind.BLOCK
            "namespace" -> RegionKind.NAMESPACE
            "call" -> RegionKind.CALL
            "text" -> RegionKind.TEXT
            "doc" -> RegionKind.DOC
            else -> RegionKind.CALL
        }

        private fun regionLabel(tag: TagFact): String {
            return when (tag.keyword) {
                "def" -> tag.value("name")?.trim().orEmpty()
                "block", "namespace" -> tag.value("name")?.trim().orEmpty()
                else -> ""
            }
        }

        private fun surroundingIndent(text: String, offset: Int): String {
            var end = text.lastIndexOf('\n', (offset - 1).coerceAtLeast(0))
            while (end >= 0) {
                val start = text.lastIndexOf('\n', end - 1).let { if (it < 0) 0 else it + 1 }
                val line = text.substring(start, end)
                if (line.isNotBlank() && !isControlLine(line) && !line.trimStart().startsWith("##")) {
                    return leadingWhitespace(line)
                }
                if (start == 0) break
                end = start - 1
            }
            return ""
        }

        private fun controlRole(keyword: String): ControlRole = when {
            keyword in OPENERS -> ControlRole.OPEN
            keyword in setOf("endif", "endfor", "endwhile", "endtry", "endwith") -> ControlRole.CLOSE
            keyword in setOf("elif", "else", "except", "finally") -> ControlRole.INTERIOR
            else -> ControlRole.STATEMENT
        }

        private fun functionNames(code: String): List<String> {
            val names = mutableListOf<String>()
            val pattern = Regex("""(?m)^[ \t]*(?:async[ \t]+)?def[ \t]+([A-Za-z_][A-Za-z0-9_]*)""")
            for (match in pattern.findAll(code)) names += match.groupValues[1]
            return names
        }
    }
}
