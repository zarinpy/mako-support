package com.mako.support.inject

import com.intellij.openapi.util.TextRange
import com.intellij.psi.PsiFile
import com.intellij.psi.util.CachedValueProvider
import com.intellij.psi.util.CachedValuesManager
import com.mako.support.lexer.MakoTokenTypes
import com.mako.support.psi.MakoAttribute
import com.mako.support.psi.MakoClosingTag
import com.mako.support.psi.MakoControlStatement
import com.mako.support.psi.MakoModuleBlock
import com.mako.support.psi.MakoPythonBlock
import com.mako.support.psi.MakoTag

internal class MakoInjectionModel private constructor(
    private val moduleBodies: List<ModuleBody>,
    private val enableLoop: Boolean,
    private val pageParameters: List<String>,
    private val topLevelDefs: List<DefRegion>,
    private val forRanges: List<TextRange>,
) {
    data class RenderHeader(val text: String, val indent: String)

    fun renderHeader(offset: Int): RenderHeader {
        val builder = StringBuilder()
        appendCode(builder, moduleBodies.joinToString("") { ensureTrailingNewline(it.text) })
        appendCode(builder, MakoStubs.preamble)
        for (name in pageParameters) builder.append(name).append(" = UNDEFINED\n")
        val chain = chainAt(offset)
        val openIds = chain.map { it.id }.toSet()
        for (def in topLevelDefs) {
            if (def.emitStub && def.id !in openIds) {
                builder.append(def.closedStub()).append('\n')
            }
        }
        val indent = openChain(builder, chain, insideFor(offset))
        if (builder.isNotEmpty() && builder.last() != '\n') builder.append('\n')
        return RenderHeader(builder.toString(), indent)
    }

    fun modulePrefix(bodyStart: Int): String {
        val builder = StringBuilder()
        for (body in moduleBodies) {
            if (body.end <= bodyStart) appendCode(builder, body.text)
        }
        return builder.toString()
    }

    fun insideFor(offset: Int): Boolean = enableLoop && forRanges.any { offset >= it.startOffset && offset < it.endOffset }

    private fun chainAt(offset: Int): List<DefRegion> {
        val chain = mutableListOf<DefRegion>()
        var level = topLevelDefs
        while (true) {
            val next = level.firstOrNull { it.contains(offset) } ?: break
            chain += next
            level = next.children
        }
        return chain
    }

    companion object {
        fun of(file: PsiFile): MakoInjectionModel {
            return CachedValuesManager.getCachedValue(file) {
                CachedValueProvider.Result.create(build(file), file)
            }
        }

        private fun build(file: PsiFile): MakoInjectionModel {
            val moduleBodies = mutableListOf<ModuleBody>()
            val pageParameters = linkedSetOf<String>()
            val forRanges = mutableListOf<TextRange>()
            val topLevelDefs = mutableListOf<DefRegion>()
            val stack = ArrayDeque<Frame>()
            var enableLoop = true
            var sawPage = false
            var nextId = 0

            fun currentParent(): DefRegion? = stack.filterIsInstance<TagFrame>().lastOrNull { it.def != null }?.def

            fun addDef(region: DefRegion) {
                val parent = currentParent()
                if (parent == null) topLevelDefs += region else parent.children += region
            }

            for (child in file.children) {
                when (child) {
                    is MakoModuleBlock -> {
                        val body = child.node.findChildByType(MakoTokenTypes.PY_BODY) ?: continue
                        moduleBodies += ModuleBody(body.startOffset + body.textLength, body.text)
                    }
                    is MakoTag -> {
                        val keyword = child.node.findChildByType(MakoTokenTypes.TAG_KEYWORD)?.text ?: continue
                        val selfClosing = child.node.findChildByType(MakoTokenTypes.TAG_END)?.text == "/>"
                        if (keyword == "page" && !sawPage) {
                            sawPage = true
                            val loop = attributeValue(child, "enable_loop")
                            if (loop != null && (loop.equals("false", ignoreCase = true) || loop == "0")) {
                                enableLoop = false
                            }
                            val args = attributeValue(child, "args")
                            if (args != null && !args.contains("\${")) pageParameters += parameterNames(args)
                        }
                        val def = defRegion(child, keyword, nextId)
                        if (def != null) {
                            nextId++
                            def.bodyStart = child.textRange.endOffset
                            addDef(def)
                        }
                        if (!selfClosing && (def != null || keyword == "text" || keyword == "doc")) {
                            stack.addLast(TagFrame(keyword, def))
                        } else if (def != null) {
                            def.bodyEnd = def.bodyStart
                        }
                    }
                    is MakoClosingTag -> {
                        val keyword = child.node.findChildByType(MakoTokenTypes.TAG_KEYWORD)?.text ?: continue
                        val closeAt = child.textRange.startOffset
                        while (stack.isNotEmpty()) {
                            when (val frame = stack.removeLast()) {
                                is ForFrame -> forRanges += TextRange(frame.start, closeAt)
                                is KindFrame -> Unit
                                is TagFrame -> {
                                    frame.def?.bodyEnd = closeAt
                                    if (frame.kind == keyword) break
                                }
                            }
                        }
                    }
                    is MakoPythonBlock -> {
                        val body = child.node.findChildByType(MakoTokenTypes.PY_BODY)?.text ?: continue
                        addLocals(stack, body)
                    }
                    is MakoControlStatement -> {
                        val keyword = controlKeyword(child.text)
                        when (keyword) {
                            "for" -> stack.addLast(ForFrame(child.textRange.endOffset))
                            "endfor" -> closeFor(stack, forRanges, child.textRange.startOffset)
                            "if", "while", "try", "with" -> stack.addLast(KindFrame(keyword))
                            "endif", "endwhile", "endtry", "endwith" -> closeKind(stack, keyword.removePrefix("end"))
                        }
                        val body = controlBody(child.text) ?: continue
                        addLocals(stack, body.range.substring(child.text))
                    }
                }
            }
            val fileEnd = file.textLength
            while (stack.isNotEmpty()) {
                when (val frame = stack.removeLast()) {
                    is ForFrame -> forRanges += TextRange(frame.start, fileEnd)
                    is TagFrame -> frame.def?.bodyEnd = fileEnd
                    is KindFrame -> Unit
                }
            }
            return MakoInjectionModel(moduleBodies, enableLoop, pageParameters.toList(), topLevelDefs, forRanges)
        }

        private fun defRegion(tag: MakoTag, keyword: String, id: Int): DefRegion? {
            return when (keyword) {
                "def" -> {
                    val value = attributeValue(tag, "name") ?: return null
                    val signature = if (value.contains("\${")) null else parseSignature(value)
                    DefRegion(
                        id = id,
                        name = signature?.name ?: "_mako",
                        parameters = signature?.parameters ?: "",
                        parameterNames = signature?.parameterNames ?: emptySet(),
                        emitStub = signature != null,
                    )
                }
                "block" -> {
                    val value = attributeValue(tag, "name") ?: return null
                    val name = plainIdentifier(value) ?: return null
                    val args = attributeValue(tag, "args")
                    val parameters = if (args != null && !args.contains("\${")) args.trim() else ""
                    DefRegion(
                        id = id,
                        name = name,
                        parameters = parameters,
                        parameterNames = if (parameters.isEmpty()) emptySet() else parameterNames(parameters),
                        emitStub = true,
                    )
                }
                else -> null
            }
        }

        private fun addLocals(stack: ArrayDeque<Frame>, code: String) {
            val def = stack.filterIsInstance<TagFrame>().lastOrNull { it.def != null }?.def ?: return
            for (name in declaredNames(code)) {
                if (name != def.name && name !in def.parameterNames) def.locals += name
            }
        }

        private fun closeFor(stack: ArrayDeque<Frame>, ranges: MutableList<TextRange>, end: Int) {
            while (stack.isNotEmpty()) {
                val frame = stack.last()
                if (frame is TagFrame) return
                stack.removeLast()
                if (frame is ForFrame) {
                    ranges += TextRange(frame.start, end)
                    return
                }
            }
        }

        private fun closeKind(stack: ArrayDeque<Frame>, kind: String) {
            while (stack.isNotEmpty()) {
                val frame = stack.last()
                if (frame is TagFrame) return
                stack.removeLast()
                if (frame is KindFrame && frame.kind == kind) return
            }
        }

        private fun attributeValue(tag: MakoTag, name: String): String? {
            val attribute = tag.children.filterIsInstance<MakoAttribute>().firstOrNull { attribute ->
                attribute.node.findChildByType(MakoTokenTypes.ATTR_NAME)?.text == name
            } ?: return null
            val raw = attribute.node.findChildByType(MakoTokenTypes.ATTR_VALUE)?.text ?: return null
            val inner = unquote(raw) ?: return null
            return inner.substring(raw)
        }

        private fun plainIdentifier(value: String): String? {
            val text = value.trim()
            if (text.isEmpty()) return null
            if (!(text.first().isLetter() || text.first() == '_')) return null
            if (text.any { !(it.isLetterOrDigit() || it == '_') }) return null
            return text
        }

        private fun openChain(builder: StringBuilder, chain: List<DefRegion>, loop: Boolean): String {
            var indent = ""
            for ((index, def) in chain.withIndex()) {
                if (def.emitStub) {
                    builder.append(indent).append("def ").append(def.name).append('(').append(def.parameters).append("):\n")
                    indent += "    "
                    for (local in def.locals) {
                        builder.append(indent).append(local).append(" = None\n")
                    }
                }
                val next = chain.getOrNull(index + 1)?.id
                for (child in def.children) {
                    if (child.emitStub && child.id != next) {
                        builder.append(indentBlock(indent, child.closedStub())).append('\n')
                    }
                }
            }
            if (loop) builder.append(indent).append("loop: LoopContext\n")
            return indent
        }

        private fun appendCode(builder: StringBuilder, code: String) {
            if (code.isEmpty()) return
            builder.append(code)
            if (builder.last() != '\n') builder.append('\n')
        }

        private fun ensureTrailingNewline(text: String): String {
            return if (text.isEmpty() || text.endsWith('\n')) text else text + "\n"
        }

        private fun indentBlock(indent: String, block: String): String {
            if (indent.isEmpty()) return block
            return block.lineSequence().joinToString("\n") { line ->
                if (line.isEmpty()) line else indent + line
            }
        }
    }
}

private class ModuleBody(val end: Int, val text: String)

private class DefRegion(
    val id: Int,
    val name: String,
    val parameters: String,
    val parameterNames: Set<String>,
    val emitStub: Boolean,
    var bodyStart: Int = 0,
    var bodyEnd: Int = Int.MAX_VALUE,
    val locals: MutableSet<String> = linkedSetOf(),
    val children: MutableList<DefRegion> = mutableListOf(),
) {
    fun contains(offset: Int): Boolean = offset >= bodyStart && offset < bodyEnd

    fun closedStub(): String = "def $name($parameters):\n    pass"
}

private sealed class Frame
private class TagFrame(val kind: String, val def: DefRegion?) : Frame()
private class ForFrame(val start: Int) : Frame()
private class KindFrame(val kind: String) : Frame()
