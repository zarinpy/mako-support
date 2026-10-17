package com.mako.support.run

import com.intellij.execution.filters.ConsoleFilterProvider
import com.intellij.execution.filters.Filter
import com.intellij.execution.filters.OpenFileHyperlinkInfo
import com.intellij.openapi.fileTypes.FileTypeManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.roots.ProjectRootManager
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.openapi.vfs.VirtualFileManager

/**
 * Links Mako's text traceback (`File "...", line N`) and `CompileException`
 * (`in file '...' at line: N char: M`) back to the template. Python files are
 * left for the Python console filter.
 */
class MakoTracebackFilter(private val project: Project) : Filter {
    override fun applyFilter(line: String, entireLength: Int): Filter.Result? {
        return link(
            line,
            entireLength,
            FILE_LINE.find(line),
            columnGroup = null,
        ) ?: link(
            line,
            entireLength,
            COMPILE.find(line),
            columnGroup = 3,
        ) ?: link(
            line,
            entireLength,
            HTML.find(line),
            columnGroup = null,
        )
    }

    private fun link(line: String, entireLength: Int, match: MatchResult?, columnGroup: Int?): Filter.Result? {
        if (match == null) return null
        val path = match.groupValues[1]
        val file = resolve(path) ?: return null
        if (!isMako(path, file)) return null
        val lineNumber = (match.groupValues[2].toInt() - 1).coerceAtLeast(0)
        val column = if (columnGroup == null) {
            0
        } else {
            (match.groupValues[columnGroup].toInt() - 1).coerceAtLeast(0)
        }
        val range = match.groups[1]?.range ?: return null
        val base = entireLength - line.length
        return Filter.Result(
            base + range.first,
            base + range.last + 1,
            OpenFileHyperlinkInfo(project, file, lineNumber, column),
        )
    }

    private fun resolve(path: String): VirtualFile? {
        val normalized = path.trim().replace('\\', '/')
        if (normalized.isEmpty()) return null
        val direct = LocalFileSystem.getInstance().findFileByPath(normalized)
        if (direct != null && !direct.isDirectory) return direct
        val manager = VirtualFileManager.getInstance()
        for (url in listOf("temp://$normalized", "file://$normalized")) {
            val found = manager.findFileByUrl(url)
            if (found != null && !found.isDirectory) return found
        }
        val relative = normalized.removePrefix("/")
        for (root in ProjectRootManager.getInstance(project).contentRoots) {
            val rootPath = root.path.trimEnd('/')
            if (normalized == rootPath && !root.isDirectory) return root
            if (normalized.startsWith("$rootPath/")) {
                val underRoot = root.findFileByRelativePath(normalized.removePrefix("$rootPath/"))
                if (underRoot != null && !underRoot.isDirectory) return underRoot
            }
            val under = root.findFileByRelativePath(relative)
            if (under != null && !under.isDirectory) return under
        }
        return null
    }

    private fun isMako(path: String, file: VirtualFile): Boolean {
        val written = path.substringAfterLast('.').substringBefore('?').lowercase()
        if (written == "py") return false
        val extension = file.extension?.lowercase()
        if (extension == "mako" || extension == "mak") return true
        return FileTypeManager.getInstance().getFileTypeByFile(file).name == "Mako"
    }

    companion object {
        private val FILE_LINE = Regex("""File "([^"]+)", line (\d+),""")
        private val COMPILE = Regex(""" in file '([^']+)' at line: (\d+) char: (\d+)""")
        private val HTML = Regex("""([^\s<"]+\.(?:mako|mak)), line (\d+):""")
    }
}

class MakoTracebackFilterProvider : ConsoleFilterProvider {
    override fun getDefaultFilters(project: Project): Array<Filter> = arrayOf(MakoTracebackFilter(project))
}
