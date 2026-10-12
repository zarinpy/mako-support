package com.mako.support.template

import com.intellij.lang.Language
import com.intellij.lang.LanguageParserDefinitions
import com.intellij.lang.html.HTMLLanguage
import com.intellij.openapi.fileTypes.PlainTextLanguage
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.LanguageSubstitutors
import com.intellij.psi.MultiplePsiFilesPerDocumentFileViewProvider
import com.intellij.psi.PsiFile
import com.intellij.psi.PsiManager
import com.intellij.psi.impl.source.PsiFileImpl
import com.intellij.psi.impl.source.PsiPlainTextFileImpl
import com.intellij.psi.templateLanguages.ConfigurableTemplateLanguageFileViewProvider
import com.intellij.psi.templateLanguages.TemplateDataLanguageMappings
import com.intellij.psi.templateLanguages.TemplateLanguage
import com.intellij.psi.tree.IElementType
import com.mako.support.lang.MakoLanguage

class MakoFileViewProvider private constructor(
    manager: PsiManager,
    file: VirtualFile,
    eventSystemEnabled: Boolean,
    private val dataLanguage: Language,
) : MultiplePsiFilesPerDocumentFileViewProvider(manager, file, eventSystemEnabled),
    ConfigurableTemplateLanguageFileViewProvider {

    constructor(manager: PsiManager, file: VirtualFile, eventSystemEnabled: Boolean) : this(
        manager,
        file,
        eventSystemEnabled,
        templateDataLanguage(file, manager.project),
    )

    override fun getBaseLanguage(): Language = MakoLanguage

    override fun getTemplateDataLanguage(): Language = dataLanguage

    override fun getLanguages(): Set<Language> = setOf(MakoLanguage, dataLanguage)

    override fun createFile(language: Language): PsiFile? {
        if (language == MakoLanguage) {
            return LanguageParserDefinitions.INSTANCE.forLanguage(language).createFile(this)
        }
        if (language != dataLanguage) return null
        val definition = LanguageParserDefinitions.INSTANCE.forLanguage(language)
        val psi = if (definition == null) PsiPlainTextFileImpl(this) else definition.createFile(this)
        (psi as PsiFileImpl).setContentElementType(MakoTemplateDataElementType)
        return psi
    }

    override fun cloneInner(fileCopy: VirtualFile): MultiplePsiFilesPerDocumentFileViewProvider {
        return MakoFileViewProvider(manager, fileCopy, false, dataLanguage)
    }

    companion object {
        fun templateDataLanguage(file: VirtualFile, project: Project): Language {
            val mapped = TemplateDataLanguageMappings.getInstance(project).getMapping(file)
                ?: HTMLLanguage.INSTANCE
            if (mapped is TemplateLanguage) return PlainTextLanguage.INSTANCE
            return LanguageSubstitutors.getInstance().substituteLanguage(mapped, file, project)
        }
    }
}
