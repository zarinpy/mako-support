package com.mako.support.run

import com.intellij.execution.ExecutionException
import com.intellij.execution.configurations.GeneralCommandLine
import com.intellij.execution.filters.TextConsoleBuilderFactory
import com.intellij.execution.process.OSProcessHandler
import com.intellij.execution.process.ProcessHandler
import com.intellij.execution.ui.ConsoleView
import com.intellij.lang.injection.InjectedLanguageManager
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.PathManager
import com.intellij.openapi.components.PersistentStateComponent
import com.intellij.openapi.components.State
import com.intellij.openapi.components.Storage
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.openapi.ui.Messages
import com.intellij.openapi.vfs.VfsUtilCore
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.openapi.wm.ToolWindow
import com.intellij.openapi.wm.ToolWindowFactory
import com.intellij.openapi.wm.ToolWindowManager
import com.intellij.psi.PsiFile
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.components.JBTextArea
import com.intellij.ui.components.JBTextField
import com.intellij.ui.content.ContentFactory
import com.jetbrains.python.sdk.PythonSdkUtil
import com.mako.support.edit.MakoTemplates
import com.mako.support.lang.MakoLanguage
import java.awt.BorderLayout
import java.awt.Dimension
import java.io.File
import java.nio.charset.StandardCharsets
import javax.swing.JComponent
import javax.swing.JPanel

/** Output encoding is the only project setting Phase 6 owns. Empty means a Unicode string. */
@State(name = "MakoProjectSettings", storages = [Storage("mako.xml")])
class MakoProjectSettings : PersistentStateComponent<MakoProjectSettings.State> {
    class State {
        @JvmField
        var outputEncoding: String = ""
    }

    private var state = State()

    override fun getState(): State = state

    override fun loadState(state: State) {
        this.state = state
    }

    var outputEncoding: String
        get() = state.outputEncoding
        set(value) {
            state.outputEncoding = value
        }

    companion object {
        fun getInstance(project: Project): MakoProjectSettings = project.getService(MakoProjectSettings::class.java)
    }
}

internal data class MakoPreviewTarget(val uri: String, val directories: List<VirtualFile>)

/** URI and lookup directories the preview action passes to `TemplateLookup`. */
internal fun previewTarget(file: PsiFile): MakoPreviewTarget? {
    val virtual = file.virtualFile ?: return null
    val roots = MakoTemplates.roots(file)
    val containing = roots.firstOrNull { VfsUtilCore.isAncestor(it, virtual, false) }
    if (containing != null) {
        val uri = VfsUtilCore.getRelativePath(virtual, containing, '/') ?: virtual.name
        return MakoPreviewTarget(uri, roots)
    }
    val parent = virtual.parent ?: return null
    return MakoPreviewTarget(virtual.name, listOf(parent))
}

class MakoPreviewSession(private val project: Project) {
    private var console: ConsoleView? = null
    private var handler: ProcessHandler? = null

    fun console(): ConsoleView {
        console?.let { return it }
        val builder = TextConsoleBuilderFactory.getInstance().createBuilder(project)
        builder.addFilter(MakoTracebackFilter(project))
        val view = builder.console
        console = view
        return view
    }

    fun preview(file: PsiFile, json: String, encoding: String) {
        val window = ToolWindowManager.getInstance(project).getToolWindow(TOOL_WINDOW_ID)
        if (window == null) {
            Messages.showErrorDialog(project, "The Mako Preview tool window is not available.", TITLE)
            return
        }
        window.activate({ launch(file, json.ifBlank { "{}" }, encoding) }, true)
    }

    private fun launch(file: PsiFile, json: String, encoding: String) {
        val sdk = PythonSdkUtil.findPythonSdk(file)
        val home = sdk?.homePath
        if (home.isNullOrBlank()) {
            Messages.showErrorDialog(
                project,
                "Select a Python SDK for this module before previewing a Mako template.",
                TITLE,
            )
            return
        }
        val target = previewTarget(file)
        if (target == null) {
            Messages.showErrorDialog(project, "This template has no file to render.", TITLE)
            return
        }
        val script = try {
            scriptFile()
        } catch (e: java.io.IOException) {
            Messages.showErrorDialog(project, e.message ?: "Could not write the preview script.", TITLE)
            return
        }
        val command = GeneralCommandLine(home, script.absolutePath)
            .withCharset(StandardCharsets.UTF_8)
            .withRedirectErrorStream(true)
            .withWorkDirectory(file.virtualFile?.parent?.path)
            .withEnvironment("MAKO_PREVIEW_DIRS", target.directories.joinToString(File.pathSeparator) { it.path })
            .withEnvironment("MAKO_PREVIEW_URI", target.uri)
            .withEnvironment("MAKO_PREVIEW_ENCODING", encoding)
        ApplicationManager.getApplication().executeOnPooledThread {
            val process = try {
                OSProcessHandler(command)
            } catch (e: ExecutionException) {
                ApplicationManager.getApplication().invokeLater {
                    if (!project.isDisposed) {
                        Messages.showErrorDialog(project, e.message ?: "Could not start the project interpreter.", TITLE)
                    }
                }
                return@executeOnPooledThread
            }
            ApplicationManager.getApplication().invokeLater {
                if (project.isDisposed) {
                    process.destroyProcess()
                    return@invokeLater
                }
                handler?.destroyProcess()
                handler = process
                val view = console()
                view.clear()
                view.attachToProcess(process)
                process.startNotify()
                val input = process.processInput
                input.write(json.toByteArray(StandardCharsets.UTF_8))
                input.flush()
                input.close()
            }
        }
    }

    private fun scriptFile(): File {
        val directory = File(PathManager.getTempPath(), "mako-preview")
        if (!directory.isDirectory && !directory.mkdirs()) {
            throw java.io.IOException("Could not create ${directory.path}")
        }
        val script = File(directory, "mako_preview.py")
        val bytes = javaClass.getResourceAsStream("/preview/mako_preview.py")?.use { it.readBytes() }
            ?: throw java.io.IOException("Mako preview script is missing from the plugin.")
        script.writeBytes(bytes)
        return script
    }

    companion object {
        const val TOOL_WINDOW_ID = "Mako Preview"

        fun getInstance(project: Project): MakoPreviewSession = project.getService(MakoPreviewSession::class.java)
    }
}

private const val TITLE = "Mako: Preview Template"

class MakoPreviewToolWindowFactory : ToolWindowFactory, DumbAware {
    override fun createToolWindowContent(project: Project, toolWindow: ToolWindow) {
        if (toolWindow.contentManager.contentCount > 0) return
        val console = MakoPreviewSession.getInstance(project).console()
        val content = ContentFactory.getInstance().createContent(console.component, "", false)
        content.isCloseable = false
        toolWindow.contentManager.addContent(content)
    }
}

class MakoPreviewAction : AnAction(), DumbAware {
    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT

    override fun update(event: AnActionEvent) {
        val file = event.getData(CommonDataKeys.PSI_FILE)
        val enabled = event.project != null && file != null && topLevel(file).language.`is`(MakoLanguage)
        event.presentation.isEnabledAndVisible = enabled
    }

    override fun actionPerformed(event: AnActionEvent) {
        val project = event.project ?: return
        val file = event.getData(CommonDataKeys.PSI_FILE)?.let(::topLevel) ?: return
        if (!file.language.`is`(MakoLanguage)) return
        val dialog = MakoPreviewDialog(project)
        if (!dialog.showAndGet()) return
        val encoding = dialog.encodingText
        MakoProjectSettings.getInstance(project).outputEncoding = encoding
        MakoPreviewSession.getInstance(project).preview(file, dialog.contextText, encoding)
    }

    private fun topLevel(file: PsiFile): PsiFile {
        return InjectedLanguageManager.getInstance(file.project).getTopLevelFile(file) ?: file
    }
}

private class MakoPreviewDialog(project: Project) : DialogWrapper(project) {
    private val contextField = JBTextArea(8, 40).apply { text = "{}" }
    private val encodingField = JBTextField().apply {
        text = MakoProjectSettings.getInstance(project).outputEncoding
    }

    val contextText: String get() = contextField.text
    val encodingText: String get() = encodingField.text.trim()

    init {
        title = TITLE
        init()
    }

    override fun createCenterPanel(): JComponent {
        val panel = JPanel(BorderLayout(0, 8))
        panel.add(JBLabel("Context (JSON object)"), BorderLayout.NORTH)
        val fields = JPanel(BorderLayout(0, 8))
        fields.add(JBScrollPane(contextField), BorderLayout.CENTER)
        val encoding = JPanel(BorderLayout(8, 0))
        encoding.add(JBLabel("Output encoding"), BorderLayout.WEST)
        encoding.add(encodingField, BorderLayout.CENTER)
        fields.add(encoding, BorderLayout.SOUTH)
        panel.add(fields, BorderLayout.CENTER)
        panel.preferredSize = Dimension(480, 240)
        return panel
    }
}
