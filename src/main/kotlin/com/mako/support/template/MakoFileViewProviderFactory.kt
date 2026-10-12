package com.mako.support.template

import com.intellij.lang.Language
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.FileViewProvider
import com.intellij.psi.FileViewProviderFactory
import com.intellij.psi.PsiManager

class MakoFileViewProviderFactory : FileViewProviderFactory {
    override fun createFileViewProvider(
        file: VirtualFile,
        language: Language,
        manager: PsiManager,
        eventSystemEnabled: Boolean,
    ): FileViewProvider = MakoFileViewProvider(manager, file, eventSystemEnabled)
}
