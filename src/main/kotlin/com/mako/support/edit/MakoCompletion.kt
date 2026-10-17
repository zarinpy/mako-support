package com.mako.support.edit

import com.intellij.codeInsight.completion.CompletionContributor
import com.intellij.codeInsight.completion.CompletionInitializationContext
import com.intellij.codeInsight.completion.CompletionParameters
import com.intellij.codeInsight.completion.CompletionResultSet
import com.intellij.codeInsight.completion.InsertHandler
import com.intellij.codeInsight.completion.InsertionContext
import com.intellij.codeInsight.lookup.LookupElement
import com.intellij.codeInsight.lookup.LookupElementInsertStopper
import com.intellij.injected.editor.DocumentWindow
import com.intellij.lang.injection.InjectedLanguageManager
import com.intellij.openapi.editor.Document
import com.intellij.psi.PsiFile
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.application.options.CodeStyle
import com.intellij.psi.impl.source.tree.injected.InjectedLanguageEditorUtil
import com.mako.support.inject.unquote
import com.mako.support.lang.MakoLanguage
import com.mako.support.lexer.MakoTokenTypes
import com.mako.support.psi.MakoAttribute
import com.mako.support.psi.MakoClosingTag
import com.mako.support.psi.MakoFilterList
import com.mako.support.psi.MakoTag

class MakoCompletionContributor : CompletionContributor() {
    override fun beforeCompletion(context: CompletionInitializationContext) {
        adjustCompletionStart(context)
    }

    override fun fillCompletionVariants(parameters: CompletionParameters, result: CompletionResultSet) {
        fillMakoCompletion(parameters, result, stopWhenHandled = false)
    }
}

class MakoTagPrefixContributor : CompletionContributor() {
    override fun beforeCompletion(context: CompletionInitializationContext) {
        adjustCompletionStart(context)
    }

    override fun fillCompletionVariants(parameters: CompletionParameters, result: CompletionResultSet) {
        fillMakoCompletion(parameters, result, stopWhenHandled = true)
    }
}

private enum class InsertKind { TEXT, ATTRIBUTE, OPENER, CONTROL, NAMESPACE }

private open class Spot(val replaceStart: Int, val prefix: String)

private class HostInsert(
    private val hostStart: Int,
    private val hostEnd: Int,
    private val replacement: String,
    private val caretInReplacement: Int,
    private val kind: InsertKind,
) : InsertHandler<LookupElement> {
    override fun handleInsert(context: InsertionContext, item: LookupElement) {
        context.setAddCompletionChar(false)
        val manager = InjectedLanguageManager.getInstance(context.project)
        val editor = InjectedLanguageEditorUtil.getTopLevelEditor(context.editor)
        val document = editor.document
        val mako = makoPsi(manager.getTopLevelFile(context.file))
        var text = replacement
        var relativeCaret = caretInReplacement
        val rangeEnd = if (hostStart == hostEnd) hostEnd + typedPrefix(document, hostStart, text) else hostEnd
        if (kind == InsertKind.NAMESPACE && (rangeEnd >= document.textLength || document.charsSequence[rangeEnd] != '>')) {
            text += ">"
            relativeCaret = text.length
        }
        if (kind == InsertKind.ATTRIBUTE && hostStart > 0 && !document.charsSequence[hostStart - 1].isWhitespace()) {
            text = " $text"
            relativeCaret += 1
        }
        if ((kind == InsertKind.CONTROL || kind == InsertKind.OPENER) &&
            hostStart == rangeEnd &&
            hostStart > 0 && document.charsSequence[hostStart - 1] == '%'
        ) {
            text = " $text"
            relativeCaret += 1
        }
        document.replaceString(hostStart, rangeEnd, text)
        val caret = if (kind == InsertKind.OPENER && mako != null) {
            openControlBlock(document, hostStart, CodeStyle.getIndentOptions(mako).INDENT_SIZE)
        } else {
            hostStart + relativeCaret
        }
        editor.caretModel.moveToOffset(caret)
        val tail = when (val current = context.document) {
            document -> caret
            is DocumentWindow -> current.hostToInjected(caret)
            else -> -1
        }
        if (tail >= 0) context.setTailOffset(tail)
        context.setLaterRunnable { editor.caretModel.moveToOffset(caret) }
    }
}

/** Characters already typed when the completion copy collapsed the replace range to a point. */
private fun typedPrefix(document: Document, offset: Int, replacement: String): Int {
    val text = document.charsSequence
    var index = 0
    while (offset + index < text.length && index < replacement.length && text[offset + index] == replacement[index]) {
        index++
    }
    return index
}

private class MakoLookup(
    private val lookup: String,
    private val insert: HostInsert,
) : LookupElement(), LookupElementInsertStopper {
    override fun getLookupString(): String = lookup

    override fun shouldStopLookupInsertion(): Boolean = true

    override fun handleInsert(context: InsertionContext) {
        insert.handleInsert(context, this)
    }
}

private fun fillMakoCompletion(parameters: CompletionParameters, result: CompletionResultSet, stopWhenHandled: Boolean) {
    val located = locate(parameters) ?: return
    val (file, caret) = located
    val spot = spotAt(file, caret) ?: return
    val manager = InjectedLanguageManager.getInstance(file.project)
    val originalMako = makoPsi(manager.getTopLevelFile(parameters.originalFile))
    val originalText = originalMako?.text ?: parameters.originalFile.text
    if ((spot is FileSpot || spot is ModuleSpot) && dummyReplacedInterpolation(file.text, originalText)) return
    val scope = originalMako?.takeIf { it.virtualFile != null }
        ?: makoPsi(parameters.originalFile)?.takeIf { it.virtualFile != null }
        ?: file
    val elements = items(scope, file, caret, spot)
    if (elements.isEmpty()) return
    val prefixed = result.withPrefixMatcher(spot.prefix)
    elements.forEach { prefixed.addElement(it) }
    if (stopWhenHandled) result.stopHere()
}

private fun adjustCompletionStart(context: CompletionInitializationContext) {
    val located = locate(context.file, context.editor.document, context.caret.offset) ?: return
    val spot = spotAt(located.first, located.second) ?: return
    val document = context.editor.document
    val mapped = if (document is DocumentWindow) document.hostToInjected(spot.replaceStart) else spot.replaceStart
    if (mapped >= 0) context.offsetMap.addOffset(CompletionInitializationContext.START_OFFSET, mapped)
}

private fun locate(parameters: CompletionParameters): Pair<PsiFile, Int>? {
    val original = makoPsi(parameters.originalFile)
    if (original != null) {
        val top = InjectedLanguageEditorUtil.getTopLevelEditor(parameters.editor)
        val offset = hostOffset(top.document.charsSequence, original.text, top.caretModel.offset)
        if (spotAt(original, offset) is NamespaceSpot) return original to offset
    }
    return locate(parameters.position.containingFile, parameters.editor.document, parameters.offset)
        ?: locate(parameters.originalFile, parameters.editor.document, parameters.offset)
}

/** Maps a caret in the completion copy back onto the original file. */
private fun hostOffset(copy: CharSequence, original: String, caret: Int): Int {
    if (copy.toString() == original) return caret.coerceIn(0, original.length)
    val dummy = CompletionInitializationContext.DUMMY_IDENTIFIER
    val text = copy.toString()
    val at = text.indexOf(dummy)
    if (at < 0) return caret.coerceIn(0, original.length)
    val oldLength = original.length - (text.length - dummy.length)
    if (oldLength < 0) return caret.coerceIn(0, original.length)
    val mapped = when {
        caret <= at -> caret
        caret >= at + dummy.length -> caret - dummy.length + oldLength
        else -> at + oldLength
    }
    return mapped.coerceIn(0, original.length)
}

/** The completion copy replaces an interpolated value with the dummy identifier. */
private fun dummyReplacedInterpolation(copy: String, original: String): Boolean {
    val dummy = CompletionInitializationContext.DUMMY_IDENTIFIER
    val at = copy.indexOf(dummy)
    if (at < 0) return false
    val prefix = copy.substring(0, at)
    val suffix = copy.substring(at + dummy.length)
    if (!original.startsWith(prefix) || !original.endsWith(suffix)) return false
    if (prefix.length > original.length - suffix.length) return false
    val replaced = original.substring(prefix.length, original.length - suffix.length)
    return replaced.contains("\${")
}

private fun locate(file: PsiFile, document: Document, offset: Int): Pair<PsiFile, Int>? {
    val manager = InjectedLanguageManager.getInstance(file.project)
    val mako = makoPsi(manager.getTopLevelFile(file)) ?: return null
    val candidates = mutableListOf<Int>()
    if (document is DocumentWindow) candidates += document.injectedToHost(offset)
    if (manager.isInjectedFragment(file)) candidates += manager.injectedToHost(file, offset)
    if (file.language == MakoLanguage || file == mako) candidates += offset
    val chosen = candidates.firstOrNull { it in 0..mako.textLength && spotAt(mako, it) != null }
        ?: candidates.firstOrNull { it in 0..mako.textLength }
        ?: return null
    return mako to chosen
}

private fun items(scope: PsiFile, file: PsiFile, caret: Int, spot: Spot): List<LookupElement> {
    val end = caret
    return when (spot) {
        is TagNameSpot -> TAG_NAMES.map { tagElement(it, spot.replaceStart, end) }
        is NamespaceSpot -> namespaceNames(scope, spot.namespace).map { word(it, spot.replaceStart, end, InsertKind.NAMESPACE) }
        is AttributeSpot -> {
            val names = TAG_ATTRIBUTES[spot.keyword].orEmpty().filter { it !in spot.present }
            names.map { attributeElement(it, spot.replaceStart, end) }
        }
        is FilterSpot -> filterNames(file, caret).map { word(it, spot.replaceStart, end, InsertKind.TEXT) }
        is WordsSpot -> spot.words.map { word(it, spot.replaceStart, end, InsertKind.TEXT) }
        is FileSpot -> MakoTemplates.completionUris(scope).map { word(it, spot.replaceStart, end, InsertKind.TEXT) }
        is ModuleSpot -> MakoTemplates.moduleNames(scope, spot.prefix).map { word(it, spot.replaceStart, end, InsertKind.TEXT) }
        is ImportSpot -> importNames(scope, spot.tag).map { word(it, spot.replaceStart, end, InsertKind.TEXT) }
        is ControlSpot -> controlNames(file, caret).map { name ->
            val kind = if (name in OPENERS) InsertKind.OPENER else InsertKind.CONTROL
            word(name, spot.replaceStart, end, kind)
        }
        else -> emptyList()
    }
}

private class TagNameSpot(start: Int, prefix: String) : Spot(start, prefix)
private class NamespaceSpot(val namespace: String, start: Int, prefix: String) : Spot(start, prefix)
private class AttributeSpot(val keyword: String, val present: Set<String>, start: Int, prefix: String) : Spot(start, prefix)
private class FilterSpot(start: Int, prefix: String) : Spot(start, prefix)
private class WordsSpot(val words: List<String>, start: Int, prefix: String) : Spot(start, prefix)
private class FileSpot(start: Int, prefix: String) : Spot(start, prefix)
private class ModuleSpot(start: Int, prefix: String) : Spot(start, prefix)
private class ImportSpot(val tag: MakoTag, start: Int, prefix: String) : Spot(start, prefix)
private class ControlSpot(start: Int, prefix: String) : Spot(start, prefix)

private fun spotAt(file: PsiFile, caret: Int): Spot? {
    if (file.language != MakoLanguage && makoPsi(file) != file) return null
    val text = file.text
    if (caret !in 0..text.length) return null
    controlPartial(text, caret)?.let { range ->
        return ControlSpot(range.startOffset, text.substring(range.startOffset, range.endOffset))
    }
    tagPartial(text, caret)?.let { partial ->
        // `<%page<caret>/>` is an attribute position: the keyword is already a
        // known tag and its closer is still ahead. Replacing the keyword would
        // offer tag skeletons instead of attributes.
        if (partial.namespace == null && partial.partial in TAG_ATTRIBUTES && tagCloserFollows(text, caret)) {
            val tag = elementAt(file, caret)?.let { PsiTreeUtil.getParentOfType(it, MakoTag::class.java, false) }
            val present = if (tag != null) presentNames(tag, caret, caret) else emptySet()
            return AttributeSpot(partial.partial, present, caret, "")
        }
        val keyword = partial.partial.substringBefore(CompletionInitializationContext.DUMMY_IDENTIFIER.trimEnd())
        val afterKeyword = skipDummy(text, caret)
        if (partial.namespace == null && keyword.isNotEmpty() && keyword !in TAG_NAMES &&
            afterKeyword < text.length && text[afterKeyword] == ':'
        ) {
            return NamespaceSpot(keyword, afterKeyword + 1, "")
        }
        return if (partial.namespace != null) {
            NamespaceSpot(partial.namespace, partial.replaceStart, partial.partial)
        } else {
            TagNameSpot(partial.replaceStart, partial.partial)
        }
    }
    return structuralSpot(file, caret)
}

private fun skipDummy(text: CharSequence, offset: Int): Int {
    var index = offset
    val dummy = CompletionInitializationContext.DUMMY_IDENTIFIER.trimEnd()
    if (index + dummy.length <= text.length && text.subSequence(index, index + dummy.length).toString() == dummy) {
        index += dummy.length
    }
    while (index < text.length && text[index].isWhitespace()) index++
    return index
}

/** True when a `>` after the caret still closes the tag being typed. */
private fun tagCloserFollows(text: CharSequence, offset: Int): Boolean {
    var index = skipDummy(text, offset)
    while (index < text.length) {
        when (val ch = text[index]) {
            '\n', '<' -> return false
            '>' -> return true
            '"', '\'' -> {
                index++
                while (index < text.length && text[index] != ch && text[index] != '\n') index++
            }
        }
        index++
    }
    return false
}

private fun structuralSpot(file: PsiFile, caret: Int): Spot? {
    val element = elementAt(file, caret) ?: return null
    if (PsiTreeUtil.getParentOfType(element, MakoClosingTag::class.java, false) != null) return null
    val type = element.node.elementType
    val filter = PsiTreeUtil.getParentOfType(element, MakoFilterList::class.java, false)
    if (filter != null || type == MakoTokenTypes.FILTER_BAR || type == MakoTokenTypes.FILTER_BODY) {
        return filterSpot(filter, caret)
    }
    val attribute = PsiTreeUtil.getParentOfType(element, MakoAttribute::class.java, false)
    val tag = attribute?.parent as? MakoTag ?: PsiTreeUtil.getParentOfType(element, MakoTag::class.java, false) ?: return null
    val keyword = tag.node.findChildByType(MakoTokenTypes.TAG_KEYWORD)?.text ?: return null
    if (attribute != null) {
        val value = attribute.node.findChildByType(MakoTokenTypes.ATTR_VALUE)
        if (value != null && caret >= value.startOffset && caret <= value.startOffset + value.textLength) {
            return valueSpot(file, tag, attribute, value.text, value.startOffset, caret)
        }
    }
    if (type == MakoTokenTypes.TAG_END || keyword.contains(':') || keyword !in TAG_ATTRIBUTES) return null
    val start = nameStart(file.text, caret)
    return AttributeSpot(keyword, presentNames(tag, start, caret), start, file.text.substring(start, caret))
}

private fun filterSpot(filter: MakoFilterList?, caret: Int): FilterSpot {
    val body = filter?.node?.findChildByType(MakoTokenTypes.FILTER_BODY)
    if (body == null) return FilterSpot(caret, "")
    val local = (caret - body.startOffset).coerceIn(0, body.textLength)
    val piece = pieceAt(body.text, local)
    return FilterSpot(body.startOffset + piece.first, piece.second)
}

private fun valueSpot(
    file: PsiFile,
    tag: MakoTag,
    attribute: MakoAttribute,
    raw: String,
    valueOffset: Int,
    caret: Int,
): Spot? {
    val name = attribute.node.findChildByType(MakoTokenTypes.ATTR_NAME)?.text ?: return null
    val inner = unquote(raw) ?: return null
    val start = valueOffset + inner.startOffset
    val end = valueOffset + inner.endOffset
    if (caret !in start..end) return null
    val value = raw.substring(inner.startOffset, inner.endOffset)
    val local = caret - start
    if (value.contains("\${") && name != "import" && name != "filter" && name != "expression_filter") return null
    val typed = value.substring(0, local.coerceIn(0, value.length))
    return when (name) {
        "filter", "expression_filter" -> {
            val piece = pieceAt(value, local)
            FilterSpot(start + piece.first, piece.second)
        }
        "cache_type" -> WordsSpot(CACHE_TYPES, start, typed)
        "file" -> FileSpot(start, typed)
        "module" -> ModuleSpot(start, typed)
        "import" -> {
            val piece = pieceAt(value, local)
            ImportSpot(tag, start + piece.first, piece.second)
        }
        in BOOLEAN_ATTRIBUTES -> WordsSpot(listOf("True", "False"), start, typed)
        else -> null
    }
}

private fun pieceAt(value: String, local: Int): Pair<Int, String> {
    val bound = local.coerceIn(0, value.length)
    var start = 0
    var index = 0
    while (index < bound) {
        if (value[index] == ',') start = index + 1
        index++
    }
    while (start < bound && value[start].isWhitespace()) start++
    return start to value.substring(start, bound)
}

private fun nameStart(text: String, caret: Int): Int {
    var start = caret
    while (start > 0 && (text[start - 1].isLetterOrDigit() || text[start - 1] == '_')) start--
    return start
}

private fun presentNames(tag: MakoTag, nameStart: Int, caret: Int): Set<String> {
    val names = linkedSetOf<String>()
    for (attribute in tag.children.filterIsInstance<MakoAttribute>()) {
        val name = attribute.node.findChildByType(MakoTokenTypes.ATTR_NAME) ?: continue
        val rangeStart = name.startOffset
        val rangeEnd = rangeStart + name.textLength
        if (caret in rangeStart..rangeEnd || nameStart in rangeStart until rangeEnd) continue
        names += name.text
    }
    return names
}

private fun tagElement(name: String, start: Int, end: Int): LookupElement {
    val (text, caret) = tagSkeleton(name)
    return word(name, start, end, InsertKind.TEXT, text, caret)
}

private fun attributeElement(name: String, start: Int, end: Int): LookupElement {
    return word(name, start, end, InsertKind.ATTRIBUTE, "$name=\"\"", "$name=\"".length)
}

private fun tagSkeleton(name: String): Pair<String, Int> {
    val text = when (name) {
        "page" -> "page args=\"\"/>"
        "include" -> "include file=\"\"/>"
        "inherit" -> "inherit file=\"\"/>"
        "namespace" -> "namespace name=\"\" file=\"\"/>"
        "def" -> "def name=\"\">\n</%def>"
        "block" -> "block name=\"\">\n</%block>"
        "call" -> "call expr=\"\">\n</%call>"
        "text" -> "text>\n\n</%text>"
        "doc" -> "doc>\n\n</%doc>"
        else -> name
    }
    val caret = when (name) {
        "page" -> "page args=\"".length
        "include" -> "include file=\"".length
        "inherit" -> "inherit file=\"".length
        "namespace" -> "namespace name=\"".length
        "def" -> "def name=\"".length
        "block" -> "block name=\"".length
        "call" -> "call expr=\"".length
        "text" -> "text>\n".length
        "doc" -> "doc>\n".length
        else -> text.length
    }
    return text to caret
}

private fun word(
    lookup: String,
    start: Int,
    end: Int,
    kind: InsertKind,
    replacement: String = lookup,
    caret: Int = replacement.length,
): LookupElement {
    return MakoLookup(lookup, HostInsert(start, end, replacement, caret, kind))
}

private fun filterNames(file: PsiFile, caret: Int): List<String> {
    val names = linkedSetOf<String>()
    names += FILTER_NAMES
    names += MakoOutline.of(file).callables(caret)
    return names.toList()
}

private fun controlNames(file: PsiFile, caret: Int): List<String> {
    val names = linkedSetOf<String>()
    names += CONTROL_KEYWORDS
    for (opener in MakoOutline.of(file).unclosedOpeners(caret)) {
        closerFor(opener)?.let { names += it }
    }
    return names.toList()
}

private fun namespaceNames(file: PsiFile, namespace: String): List<String> {
    val tag = MakoOutline.of(file).namespaces.firstOrNull { it.value("name") == namespace } ?: return emptyList()
    return exportedDefs(file, tag.value("file"), tag.value("module"))
}

private fun importNames(file: PsiFile, tag: MakoTag): List<String> {
    val names = linkedSetOf("*")
    names += exportedDefs(file, attributeText(tag, "file"), attributeText(tag, "module"))
    return names.toList()
}

private fun attributeText(tag: MakoTag, name: String): String? {
    val attribute = tag.children.filterIsInstance<MakoAttribute>().firstOrNull {
        it.node.findChildByType(MakoTokenTypes.ATTR_NAME)?.text == name
    } ?: return null
    val raw = attribute.node.findChildByType(MakoTokenTypes.ATTR_VALUE)?.text ?: return null
    val inner = unquote(raw) ?: return null
    return inner.substring(raw)
}

private fun exportedDefs(file: PsiFile, path: String?, module: String?): List<String> {
    if (!path.isNullOrBlank() && !path.contains("\${")) {
        val target = MakoTemplates.resolve(path, file) ?: return emptyList()
        return MakoOutline.of(target).topLevelDefs.map { it.name }
    }
    if (!module.isNullOrBlank() && !module.contains("\${")) return MakoTemplates.moduleFunctions(file, module)
    return emptyList()
}
