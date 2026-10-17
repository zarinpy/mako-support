package com.mako.support.run

import com.intellij.codeInsight.lookup.LookupElement
import com.intellij.codeInsight.lookup.LookupElementBuilder
import com.intellij.lang.Language
import com.intellij.openapi.fileTypes.FileTypeManager
import com.intellij.openapi.module.Module
import com.intellij.openapi.progress.ProcessCanceledException
import com.intellij.openapi.project.IndexNotReadyException
import com.intellij.openapi.roots.ProjectRootManager
import com.intellij.openapi.vfs.VfsUtilCore
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import com.intellij.psi.PsiManager
import com.intellij.psi.util.CachedValueProvider
import com.intellij.psi.util.CachedValuesManager
import com.intellij.template.lang.core.templateLanguages.PyTemplateLanguageSubstitutorDelegate
import com.intellij.template.lang.core.templateLanguages.PythonTemplateLanguage
import com.intellij.template.lang.core.templateLanguages.TemplateContextProvider
import com.intellij.template.lang.core.templateLanguages.TemplatesService
import com.jetbrains.python.psi.PyAssignmentStatement
import com.jetbrains.python.psi.PyCallExpression
import com.jetbrains.python.psi.PyDictLiteralExpression
import com.jetbrains.python.psi.PyExpression
import com.jetbrains.python.psi.PyFile
import com.jetbrains.python.psi.PyKeyValueExpression
import com.jetbrains.python.psi.PyKeywordArgument
import com.jetbrains.python.psi.PyRecursiveElementVisitor
import com.jetbrains.python.psi.PyReferenceExpression
import com.jetbrains.python.psi.PyStarArgument
import com.jetbrains.python.psi.PyStringLiteralExpression
import com.jetbrains.python.psi.PyTargetExpression
import com.mako.support.lang.MakoLanguage

/**
 * Professional-Python bridge. [MakoLanguage] stays a platform language so the
 * plugin still loads without Pythonid. This class is created only from
 * `mako-python-pro.xml`.
 *
 * The template-language dropdown stores [PythonTemplateLanguage.getTemplateLanguageName],
 * not the language id. The id cannot be "Mako" because [MakoLanguage] already
 * owns it, and the built-in HTML substitutor would then parse those files as
 * this subclass, which has no Mako parser. The delegate returns [MakoLanguage]
 * for files the module treats as templates.
 */
object MakoPythonTemplateLanguage : PythonTemplateLanguage("MakoPython") {
    override fun getTemplateLanguageName(): String = "Mako"

    override fun getDisplayName(): String = "Mako"

    override fun isFileLeadsToLanguageSelection(text: String): Boolean {
        return text.contains("<%") || text.contains("\${")
    }
}

class MakoFramework :
    PyTemplateLanguageSubstitutorDelegate(),
    MakoRenderContext,
    TemplateContextProvider {

    init {
        // Register the dropdown language as soon as either extension instantiates this class.
        MakoPythonTemplateLanguage.templateLanguageName
    }

    override fun isTemplateForbidden(file: VirtualFile, module: Module): Boolean {
        if (!selectedMako(module) || !matchesTemplateFileType(file, module)) return false
        return !coveredByTemplateFolder(file, module)
    }

    override fun getImplicitLanguage(file: VirtualFile, module: Module): Language? {
        if (!selectedMako(module) || !matchesTemplateFileType(file, module)) return null
        if (!coveredByTemplateFolder(file, module)) return null
        return MakoLanguage
    }

    override fun pageNames(file: PsiFile): List<String> {
        val physical = physical(file) ?: return emptyList()
        val own = namesFor(physical).page
        val extra = otherNames(physical)
        if (extra.isEmpty()) return own
        return (own + extra).distinct()
    }

    override fun defNames(file: PsiFile): Map<String, List<String>> {
        val physical = physical(file) ?: return emptyMap()
        return namesFor(physical).defs
    }

    override fun getTemplateContext(file: PsiFile): Collection<LookupElement> {
        if (IN_PROVIDER.get()) return emptyList()
        val language = file.language
        if (!language.`is`(MakoLanguage) && language !is MakoPythonTemplateLanguage) return emptyList()
        IN_PROVIDER.set(true)
        try {
            return pageNames(file).map { LookupElementBuilder.create(it) }
        } finally {
            IN_PROVIDER.set(false)
        }
    }

    companion object {
        private val IN_PROVIDER = ThreadLocal.withInitial { false }
        private val IN_OTHERS = ThreadLocal.withInitial { false }
        private val SCANNING = ThreadLocal.withInitial { false }

        private val PYTHON_KEYWORDS = setOf(
            "False", "None", "True", "and", "as", "assert", "async", "await", "break", "class",
            "continue", "def", "del", "elif", "else", "except", "finally", "for", "from", "global",
            "if", "import", "in", "is", "lambda", "nonlocal", "not", "or", "pass", "raise",
            "return", "try", "while", "with", "yield",
        )

        private val RESERVED = setOf(
            "context", "UNDEFINED", "STOP_RENDERING", "local", "self", "parent", "next",
            "caller", "capture", "pageargs", "loop", "_",
        )

        private val EMPTY = Contributions(emptyList(), emptyMap())

        private fun selectedMako(module: Module): Boolean {
            if (module.isDisposed) return false
            val language = TemplatesService.getInstance(module).templateLanguage ?: return false
            return language.templateLanguageName == "Mako"
        }

        private fun matchesTemplateFileType(file: VirtualFile, module: Module): Boolean {
            val name = file.name
            val manager = FileTypeManager.getInstance()
            return TemplatesService.getInstance(module).templateFileTypes.any { type ->
                manager.getAssociations(type).any { it.acceptsCharSequence(name) }
            }
        }

        private fun coveredByTemplateFolder(file: VirtualFile, module: Module): Boolean {
            val folders = TemplatesService.getInstance(module).templateFolders
            if (folders.isEmpty()) return true
            return folders.any { VfsUtilCore.isAncestor(it, file, true) }
        }

        private fun namesFor(file: PsiFile): Contributions {
            val virtual = file.virtualFile ?: return EMPTY
            val page = linkedSetOf<String>()
            val defs = linkedMapOf<String, MutableList<String>>()
            for (hit in hits(file.project)) {
                if (!matches(file.project, virtual, hit.uri)) continue
                if (hit.defName == null) {
                    page += hit.names
                } else {
                    val list = defs.getOrPut(hit.defName) { mutableListOf() }
                    for (name in hit.names) if (name !in list) list += name
                }
            }
            return Contributions(page.toList(), defs.mapValues { it.value.toList() })
        }

        /**
         * Completion probes injection on a file copy whose virtual file is null.
         * Django's TemplateContextProvider asserts in that case (selectLongest).
         * The original file is the one other providers can accept.
         */
        private fun physical(file: PsiFile): PsiFile? {
            if (file.virtualFile != null) return file
            val original = file.originalFile
            return if (original !== file && original.virtualFile != null) original else null
        }

        private fun otherNames(file: PsiFile): List<String> {
            if (file.virtualFile == null || IN_OTHERS.get()) return emptyList()
            IN_OTHERS.set(true)
            try {
                val names = linkedSetOf<String>()
                for (provider in TemplateContextProvider.EP_NAME.extensionList) {
                    if (provider.javaClass.name == MakoFramework::class.java.name) continue
                    val found = provider.getTemplateContext(file) ?: continue
                    for (element in found) {
                        val name = element.lookupString
                        if (accept(name)) names += name
                    }
                }
                return names.toList()
            } finally {
                IN_OTHERS.set(false)
            }
        }

        private fun hits(project: com.intellij.openapi.project.Project): List<Hit> {
            if (SCANNING.get()) return emptyList()
            return CachedValuesManager.getManager(project).getCachedValue(project) {
                if (SCANNING.get()) {
                    CachedValueProvider.Result.create(emptyList(), com.intellij.openapi.util.ModificationTracker.EVER_CHANGED)
                } else {
                    SCANNING.set(true)
                    try {
                        CachedValueProvider.Result.create(scan(project), PsiManager.getInstance(project).modificationTracker)
                    } finally {
                        SCANNING.set(false)
                    }
                }
            }
        }

        private fun scan(project: com.intellij.openapi.project.Project): List<Hit> {
            val hits = mutableListOf<Hit>()
            val manager = PsiManager.getInstance(project)
            for (root in ProjectRootManager.getInstance(project).contentRoots) {
                VfsUtilCore.iterateChildrenRecursively(root, null) { child ->
                    if (!child.isDirectory && child.extension.equals("py", ignoreCase = true) && child.length <= 1_000_000) {
                        collect(manager, child, hits)
                    }
                    true
                }
            }
            return hits
        }

        private fun collect(manager: PsiManager, file: VirtualFile, hits: MutableList<Hit>) {
            val text = try {
                VfsUtilCore.loadText(file)
            } catch (_: java.io.IOException) {
                return
            }
            if (!text.contains("render") && !text.contains("Template") && !text.contains("get_template") && !text.contains("get_def")) {
                return
            }
            val psi = try {
                manager.findFile(file)
            } catch (e: IndexNotReadyException) {
                throw ProcessCanceledException(e)
            }
            if (psi !is PyFile) return
            val scanner = Scanner()
            psi.accept(scanner)
            hits += scanner.hits
        }

        private fun matches(project: com.intellij.openapi.project.Project, file: VirtualFile, uri: String): Boolean {
            val normalized = uri.trim().replace('\\', '/')
            if (normalized.isEmpty() || normalized.contains("\${")) return false
            if (!normalized.contains('/') && file.name == normalized) return true
            if (file.path == normalized) return true
            val suffix = if (normalized.startsWith("/")) normalized else "/$normalized"
            if (file.path.endsWith(suffix)) return true
            val relative = normalized.removePrefix("/")
            for (root in templateRoots(project, file)) {
                val candidate = root.findFileByRelativePath(relative) ?: continue
                if (candidate == file) return true
            }
            return false
        }

        private fun templateRoots(project: com.intellij.openapi.project.Project, file: VirtualFile): List<VirtualFile> {
            val templates = ProjectRootManager.getInstance(project).contentRoots
                .mapNotNull { it.findChild("templates") }
                .filter { it.isDirectory }
            if (templates.isNotEmpty()) return templates
            return listOfNotNull(file.parent)
        }

        private fun accept(name: String): Boolean {
            if (name.isEmpty() || name in PYTHON_KEYWORDS || name in RESERVED) return false
            val first = name.first()
            if (!(first.isLetter() || first == '_')) return false
            return name.all { it.isLetterOrDigit() || it == '_' }
        }

        private fun firstString(call: PyCallExpression): String? {
            val first = call.arguments.firstOrNull() ?: return null
            if (first is PyKeywordArgument || first is PyStarArgument) return null
            val literal = first as? PyStringLiteralExpression ?: return null
            if (literal.isInterpolated) return null
            return literal.stringValue.takeIf { it.isNotEmpty() }
        }

        private fun keywordString(call: PyCallExpression, name: String): String? {
            val value = call.getKeywordArgument(name) as? PyStringLiteralExpression ?: return null
            if (value.isInterpolated) return null
            return value.stringValue.takeIf { it.isNotEmpty() }
        }

        private fun argumentNames(call: PyCallExpression, assignments: Map<String, PyExpression>): List<String> {
            val names = linkedSetOf<String>()
            for (argument in call.arguments) {
                when (argument) {
                    is PyKeywordArgument -> {
                        val keyword = argument.keyword ?: continue
                        if (accept(keyword)) names += keyword
                    }
                    is PyStarArgument -> {
                        if (!argument.isKeyword) continue
                        val value = argument.children.filterIsInstance<PyExpression>().firstOrNull() ?: continue
                        for (name in dictKeys(value, assignments)) names += name
                    }
                }
            }
            return names.toList()
        }

        private fun dictKeys(expression: PyExpression, assignments: Map<String, PyExpression>): List<String> {
            val dict = when (expression) {
                is PyDictLiteralExpression -> expression
                is PyReferenceExpression -> {
                    if (expression.qualifier != null) return emptyList()
                    assignments[expression.name] as? PyDictLiteralExpression ?: return emptyList()
                }
                else -> return emptyList()
            }
            val keys = mutableListOf<String>()
            for (child in dict.children) {
                if (child !is PyKeyValueExpression) continue
                val key = child.key as? PyStringLiteralExpression ?: continue
                if (key.isInterpolated) continue
                val name = key.stringValue
                if (accept(name)) keys += name
            }
            return keys
        }

        private fun resolveExpr(
            expression: PyExpression?,
            assignments: Map<String, PyExpression>,
            seen: MutableSet<PsiElement>,
        ): TemplateRef? {
            if (expression == null || !seen.add(expression)) return null
            return when (expression) {
                is PyReferenceExpression -> {
                    if (expression.qualifier != null) null
                    else resolveExpr(assignments[expression.name], assignments, seen)
                }
                is PyCallExpression -> resolveCall(expression, assignments, seen)
                else -> null
            }
        }

        private fun resolveCall(
            call: PyCallExpression,
            assignments: Map<String, PyExpression>,
            seen: MutableSet<PsiElement>,
        ): TemplateRef? {
            val callee = call.callee as? PyReferenceExpression ?: return null
            return when (callee.name) {
                "get_template" -> firstString(call)?.let { TemplateRef(it, null) }
                "get_def" -> {
                    val defName = firstString(call) ?: return null
                    val parent = resolveExpr(callee.qualifier, assignments, seen) ?: return null
                    if (parent.defName != null) null else parent.copy(defName = defName)
                }
                "Template" -> keywordString(call, "filename")?.let { TemplateRef(it, null) }
                else -> null
            }
        }

        private fun renderTarget(
            call: PyCallExpression,
            callee: PyReferenceExpression,
            assignments: Map<String, PyExpression>,
        ): TemplateRef? {
            val fromQualifier = resolveExpr(callee.qualifier, assignments, mutableSetOf())
            if (fromQualifier != null) return fromQualifier
            val uri = firstString(call) ?: return null
            return TemplateRef(uri, null)
        }

        private class Scanner : PyRecursiveElementVisitor() {
            val assignments = linkedMapOf<String, PyExpression>()
            val hits = mutableListOf<Hit>()

            override fun visitPyAssignmentStatement(node: PyAssignmentStatement) {
                val target = node.targets.firstOrNull() as? PyTargetExpression
                if (target != null && target.qualifier == null) {
                    val name = target.name
                    val value = node.assignedValue
                    if (name != null && value != null) assignments[name] = value
                }
                super.visitPyAssignmentStatement(node)
            }

            override fun visitPyCallExpression(node: PyCallExpression) {
                val callee = node.callee as? PyReferenceExpression
                val calleeName = callee?.name
                if (callee != null && (calleeName == "render" || calleeName == "render_template")) {
                    val target = renderTarget(node, callee, assignments)
                    if (target != null) {
                        val names = argumentNames(node, assignments)
                        if (names.isNotEmpty()) hits += Hit(target.uri, target.defName, names)
                    }
                }
                super.visitPyCallExpression(node)
            }
        }
    }
}

private data class Hit(val uri: String, val defName: String?, val names: List<String>)

private data class Contributions(val page: List<String>, val defs: Map<String, List<String>>)

private data class TemplateRef(val uri: String, val defName: String?)
