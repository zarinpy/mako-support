package com.mako.support.inject

import com.intellij.lang.ASTNode
import com.intellij.lang.injection.MultiHostInjector
import com.intellij.lang.injection.MultiHostRegistrar
import com.intellij.openapi.util.TextRange
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiLanguageInjectionHost
import com.jetbrains.python.PythonLanguage
import com.mako.support.lexer.MakoTokenTypes
import com.mako.support.psi.MakoAttribute
import com.mako.support.psi.MakoClosingTag
import com.mako.support.psi.MakoControlStatement
import com.mako.support.psi.MakoExpression
import com.mako.support.psi.MakoFilterList
import com.mako.support.psi.MakoModuleBlock
import com.mako.support.psi.MakoPythonBlock
import com.mako.support.psi.MakoTag

class MakoPythonInjector : MultiHostInjector {
    override fun elementsToInjectIn(): List<Class<out PsiElement>> = listOf(
        MakoExpression::class.java,
        MakoPythonBlock::class.java,
        MakoModuleBlock::class.java,
        MakoControlStatement::class.java,
        MakoAttribute::class.java,
    )

    override fun getLanguagesToInject(registrar: MultiHostRegistrar, context: PsiElement) {
        val file = context.containingFile ?: return
        val model = MakoInjectionModel.of(file)
        when (context) {
            is MakoExpression -> injectExpression(registrar, model, context)
            is MakoPythonBlock -> injectPythonBlock(registrar, model, context)
            is MakoModuleBlock -> injectModuleBlock(registrar, model, context)
            is MakoControlStatement -> injectControl(registrar, model, context)
            is MakoAttribute -> injectAttribute(registrar, model, context)
        }
    }

    private fun injectExpression(registrar: MultiHostRegistrar, model: MakoInjectionModel, host: MakoExpression) {
        val body = host.node.findChildByType(MakoTokenTypes.EXPR_BODY)
        if (body != null) injectExpressionRange(registrar, model, host, body.rangeIn(host))
        val filterBody = host.children.filterIsInstance<MakoFilterList>().firstOrNull()
            ?.node?.findChildByType(MakoTokenTypes.FILTER_BODY) ?: return
        injectFilterPieces(registrar, model, host, filterBody.text, filterBody.rangeIn(host).startOffset)
    }

    private fun injectPythonBlock(registrar: MultiHostRegistrar, model: MakoInjectionModel, host: MakoPythonBlock) {
        val body = host.node.findChildByType(MakoTokenTypes.PY_BODY) ?: return
        val header = model.renderHeader(host.textRange.startOffset)
        injectStatements(registrar, host, header, body.rangeIn(host))
    }

    private fun injectModuleBlock(registrar: MultiHostRegistrar, model: MakoInjectionModel, host: MakoModuleBlock) {
        val body = host.node.findChildByType(MakoTokenTypes.PY_BODY) ?: return
        registrar.place(model.modulePrefix(body.startOffset), "", host, body.rangeIn(host))
    }

    private fun injectControl(registrar: MultiHostRegistrar, model: MakoInjectionModel, host: MakoControlStatement) {
        val body = controlBody(host.text) ?: return
        val header = model.renderHeader(host.textRange.startOffset)
        val prelude = compoundPrelude(body.keyword, header.indent)
        val suffix = compoundSuffix(body.keyword, header.indent)
        registrar.place(header.text + prelude + header.indent, suffix, host, body.range)
    }

    private fun injectAttribute(registrar: MultiHostRegistrar, model: MakoInjectionModel, host: MakoAttribute) {
        if (host.parent is MakoClosingTag) return
        val valueNode = host.node.findChildByType(MakoTokenTypes.ATTR_VALUE) ?: return
        val raw = valueNode.text
        val inner = unquote(raw) ?: return
        val value = inner.substring(raw)
        val valueOrigin = valueNode.rangeIn(host).startOffset + inner.startOffset
        if (value.contains("\${")) {
            for ((expression, filters) in expressionHoles(value)) {
                if (expression.length > 0) {
                    injectExpressionRange(registrar, model, host, expression.shiftRight(valueOrigin))
                }
                for (filter in filters) {
                    val filterText = filter.substring(value)
                    if (!isBuiltinFilter(filterText)) {
                        injectExpressionRange(registrar, model, host, filter.shiftRight(valueOrigin))
                    }
                }
            }
            return
        }
        val tag = host.parent as? MakoTag ?: return
        val keyword = tag.node.findChildByType(MakoTokenTypes.TAG_KEYWORD)?.text ?: return
        val name = host.node.findChildByType(MakoTokenTypes.ATTR_NAME)?.text ?: return
        val valueRange = inner.shiftRight(valueNode.rangeIn(host).startOffset)
        when {
            keyword == "def" && name == "name" -> registrar.place("def ", ": pass", host, valueRange)
            name == "args" && (keyword == "page" || (keyword == "block" && attributeValue(tag, "name") != null)) ->
                registrar.place("def _page(", "): pass", host, valueRange)
            keyword == "call" && name == "expr" -> injectExpressionRange(registrar, model, host, valueRange)
            name == "decorator" && (keyword == "def" || keyword == "block") ->
                injectExpressionRange(registrar, model, host, valueRange)
            name == "filter" || name == "expression_filter" ->
                injectFilterPieces(registrar, model, host, value, valueRange.startOffset)
        }
    }

    private fun injectFilterPieces(
        registrar: MultiHostRegistrar,
        model: MakoInjectionModel,
        host: PsiLanguageInjectionHost,
        text: String,
        origin: Int,
    ) {
        for (piece in splitFilters(text)) {
            if (isBuiltinFilter(piece.substring(text))) continue
            injectExpressionRange(registrar, model, host, piece.shiftRight(origin))
        }
    }

    private fun injectExpressionRange(
        registrar: MultiHostRegistrar,
        model: MakoInjectionModel,
        host: PsiLanguageInjectionHost,
        range: TextRange,
    ) {
        if (range.length <= 0) return
        val header = model.renderHeader(host.textRange.startOffset)
        registrar.place(header.text + header.indent + "_ = (", ")", host, range)
    }

    private fun injectStatements(
        registrar: MultiHostRegistrar,
        host: PsiLanguageInjectionHost,
        header: MakoInjectionModel.RenderHeader,
        range: TextRange,
    ) {
        if (header.indent.isEmpty()) {
            registrar.place(header.text, "", host, range)
            return
        }
        val lines = lineRanges(host.text, range)
        if (lines.isEmpty()) return
        registrar.startInjecting(PythonLanguage.getInstance(), "py")
        lines.forEachIndexed { index, line ->
            val prefix = if (index == 0) header.text + header.indent else "\n" + header.indent
            registrar.addPlace(prefix, "", host, line)
        }
        registrar.doneInjecting()
    }

    private fun attributeValue(tag: MakoTag, name: String): String? {
        val attribute = tag.children.filterIsInstance<MakoAttribute>().firstOrNull {
            it.node.findChildByType(MakoTokenTypes.ATTR_NAME)?.text == name
        } ?: return null
        val raw = attribute.node.findChildByType(MakoTokenTypes.ATTR_VALUE)?.text ?: return null
        val inner = unquote(raw) ?: return null
        return inner.substring(raw)
    }
}

private fun MultiHostRegistrar.place(
    prefix: String,
    suffix: String,
    host: PsiLanguageInjectionHost,
    range: TextRange,
) {
    if (range.length <= 0) return
    startInjecting(PythonLanguage.getInstance(), "py")
    addPlace(prefix, suffix, host, range)
    doneInjecting()
}

private fun ASTNode.rangeIn(host: PsiElement): TextRange {
    val start = textRange.startOffset - host.textRange.startOffset
    return TextRange(start, start + textLength)
}
