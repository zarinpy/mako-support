package com.mako.support.psi

import com.intellij.extapi.psi.PsiFileBase
import com.intellij.openapi.fileTypes.FileType
import com.intellij.psi.FileViewProvider
import com.mako.support.lang.MakoFileType
import com.mako.support.lang.MakoLanguage

class MakoFile(viewProvider: FileViewProvider) : PsiFileBase(viewProvider, MakoLanguage) {
    override fun getFileType(): FileType = MakoFileType
}
