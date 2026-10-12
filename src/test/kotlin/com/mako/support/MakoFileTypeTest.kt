package com.mako.support

import com.intellij.lang.html.HTMLLanguage
import com.intellij.openapi.fileTypes.FileTypeManager
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import com.mako.support.lang.MakoFileType
import com.mako.support.lang.MakoLanguage

class MakoFileTypeTest : BasePlatformTestCase() {
    fun testMakoExtensions() {
        val types = FileTypeManager.getInstance()
        assertEquals(MakoFileType, types.getFileTypeByExtension("mako"))
        assertEquals(MakoFileType, types.getFileTypeByExtension("mak"))

        myFixture.configureByText("page.mako", "Hello")
        assertEquals(MakoLanguage, myFixture.file.language)
        myFixture.configureByText("page.mak", "Hello")
        assertEquals(MakoLanguage, myFixture.file.language)
    }

    fun testHtmlStaysHtml() {
        val html = FileTypeManager.getInstance().getFileTypeByExtension("html")
        assertFalse(html == MakoFileType)
        myFixture.configureByText("page.html", "<div></div>")
        assertEquals(HTMLLanguage.INSTANCE, myFixture.file.language)
    }
}
