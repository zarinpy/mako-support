package com.mako.support

import com.intellij.ide.plugins.PluginManagerCore
import com.intellij.openapi.extensions.PluginId
class MakoPluginTest : MakoPlatformTestCase() {

    fun testPluginLoaded() {
        val id = PluginId.getId("com.mako.support")

        assertNotNull(PluginManagerCore.getPlugin(id))
        assertFalse(PluginManagerCore.isDisabled(id))
    }
}
