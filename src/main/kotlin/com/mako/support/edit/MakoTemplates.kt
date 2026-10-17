package com.mako.support.edit

import com.intellij.openapi.project.IndexNotReadyException
import com.intellij.openapi.roots.ProjectRootManager
import com.intellij.openapi.vfs.VfsUtilCore
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.PsiFile
import com.intellij.psi.PsiManager
import com.intellij.psi.search.GlobalSearchScope
import com.intellij.psi.util.QualifiedName
import com.jetbrains.python.psi.stubs.PyModuleNameIndex
import com.jetbrains.python.sdk.PythonSdkUtil

/**
 * Template roots follow the Phase 4 defaults: a top-level `templates` directory
 * when one exists, otherwise the file's own directory. URI joining follows
 * `TemplateLookup.adjust_uri`.
 */
internal object MakoTemplates {
    fun roots(file: PsiFile): List<VirtualFile> {
        val content = ProjectRootManager.getInstance(file.project).contentRoots
        val templates = content.mapNotNull { it.findChild("templates") }.filter { it.isDirectory }
        if (templates.isNotEmpty()) return templates
        val directory = file.virtualFile?.parent ?: return emptyList()
        return listOf(directory)
    }

    fun adjustUri(uri: String, current: PsiFile): VirtualFile? {
        val trimmed = uri.trim()
        if (trimmed.isEmpty() || trimmed.contains("\${")) return null
        val templateRoots = roots(current)
        if (trimmed.startsWith("/")) {
            val relative = normalize(trimmed.removePrefix("/"))
            for (root in templateRoots) {
                val found = root.findFileByRelativePath(relative) ?: continue
                if (!found.isDirectory) return found
            }
            return null
        }
        val currentFile = current.virtualFile ?: return null
        val parent = currentFile.parent ?: return null
        val containing = templateRoots.firstOrNull { VfsUtilCore.isAncestor(it, currentFile, false) }
        if (containing == null) {
            val found = parent.findFileByRelativePath(normalize(trimmed))
            return found?.takeIf { !it.isDirectory }
        }
        val relativeDir = VfsUtilCore.getRelativePath(parent, containing, '/') ?: return null
        val joined = if (relativeDir.isEmpty()) trimmed else "$relativeDir/$trimmed"
        val found = containing.findFileByRelativePath(normalize(joined))
        return found?.takeIf { !it.isDirectory }
    }

    fun resolve(uri: String, current: PsiFile): PsiFile? {
        val file = adjustUri(uri, current) ?: return null
        return PsiManager.getInstance(current.project).findFile(file)
    }

    fun completionUris(current: PsiFile): List<String> {
        val currentFile = current.virtualFile ?: return emptyList()
        val uris = linkedSetOf<String>()
        for (root in roots(current)) {
            val under = VfsUtilCore.isAncestor(root, currentFile, false)
            for (file in makoFilesUnder(root)) {
                if (file == currentFile) continue
                val absolute = VfsUtilCore.getRelativePath(file, root, '/') ?: continue
                uris += "/$absolute"
                if (under) {
                    val from = currentFile.parent ?: continue
                    val relative = VfsUtilCore.getRelativePath(file, from, '/') ?: continue
                    uris += relative
                }
            }
        }
        return uris.toList()
    }

    fun makoFiles(file: PsiFile): List<PsiFile> {
        val manager = PsiManager.getInstance(file.project)
        val found = linkedMapOf<String, PsiFile>()
        for (root in ProjectRootManager.getInstance(file.project).contentRoots) {
            for (virtual in makoFilesUnder(root)) {
                val psi = manager.findFile(virtual) ?: continue
                found.putIfAbsent(virtual.path, psi)
            }
        }
        file.virtualFile?.let { found.putIfAbsent(it.path, file) }
        return found.values.toList()
    }

    fun moduleNames(file: PsiFile, prefix: String): List<String> {
        if (PythonSdkUtil.findPythonSdk(file) == null) return emptyList()
        return try {
            PyModuleNameIndex.getAllKeys(file.project)
                .asSequence()
                .filter { it.isNotEmpty() && (prefix.isEmpty() || it.startsWith(prefix)) }
                .take(200)
                .toList()
        } catch (_: IndexNotReadyException) {
            emptyList()
        }
    }

    fun moduleFunctions(file: PsiFile, dotted: String): List<String> {
        if (dotted.isBlank() || dotted.contains("\${")) return emptyList()
        if (PythonSdkUtil.findPythonSdk(file) == null) return emptyList()
        return try {
            val name = QualifiedName.fromDottedString(dotted)
            val files = PyModuleNameIndex.findByQualifiedName(name, file.project, GlobalSearchScope.allScope(file.project))
            files.flatMap { it.topLevelFunctions }.mapNotNull { it.name }.distinct()
        } catch (_: IndexNotReadyException) {
            emptyList()
        }
    }

    private fun makoFilesUnder(root: VirtualFile): List<VirtualFile> {
        val files = mutableListOf<VirtualFile>()
        val pending = ArrayDeque<VirtualFile>()
        pending.add(root)
        while (pending.isNotEmpty()) {
            val current = pending.removeFirst()
            for (child in current.children) {
                if (child.isDirectory) pending.add(child)
                else if (child.name.endsWith(".mako") || child.name.endsWith(".mak")) files += child
            }
        }
        return files
    }

    private fun normalize(path: String): String {
        val parts = ArrayDeque<String>()
        for (part in path.split('/')) {
            when (part) {
                "", "." -> Unit
                ".." -> if (parts.isNotEmpty()) parts.removeLast()
                else -> parts.addLast(part)
            }
        }
        return parts.joinToString("/")
    }
}
