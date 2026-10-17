package com.mako.support.run

import com.intellij.openapi.extensions.ExtensionPointName
import com.intellij.psi.PsiFile

/**
 * Names a Python caller passes into a template. The implementation lives in the
 * optional Pythonid config, so this interface is all the injector may touch.
 */
interface MakoRenderContext {
    fun pageNames(file: PsiFile): List<String>

    /** Def name to the names passed to `template.get_def(name).render(...)`. */
    fun defNames(file: PsiFile): Map<String, List<String>>

    companion object {
        val EP_NAME: ExtensionPointName<MakoRenderContext> =
            ExtensionPointName.create("com.mako.support.renderContext")

        fun of(file: PsiFile): MakoRenderContext? {
            if (file.project.isDisposed) return null
            return EP_NAME.extensionList.firstOrNull()
        }
    }
}
