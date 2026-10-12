package com.mako.support.inject

import com.intellij.openapi.application.ApplicationManager
import com.intellij.python.community.helpersLocator.PythonHelpersLocator
import com.intellij.util.PathUtil
import com.jetbrains.python.sdk.PythonSdkType
import java.nio.file.Files
import java.nio.file.Path

/**
 * PyCharm's test sandbox places Python jars in `lib/modules`. The pro helpers
 * locator treats that as a broken install and throws the first time import
 * resolution asks for typeshed. Tests point at the community helpers and drop
 * the pro locator. This stays in the test source set: the locator and the
 * startup listener that used to call it are internal API, and plugin
 * verification fails when they ship in the plugin.
 */
object MakoPythonTestLayout {
    fun prepare() {
        pointAtPythonHelpers()
        ignoreProHelpersLocator()
    }

    private fun pointAtPythonHelpers() {
        if (!System.getProperty("idea.python.helpers.path").isNullOrBlank()) return
        val jar = Path.of(PathUtil.getJarPathForClass(PythonSdkType::class.java))
        val helpers = jar.parent?.parent?.parent?.resolve("helpers")
            ?: error("Cannot locate Python helpers from $jar")
        check(Files.isDirectory(helpers)) { "Python helpers not found at $helpers" }
        System.setProperty("idea.python.helpers.path", helpers.toString())
    }

    @Suppress("DEPRECATION")
    private fun ignoreProHelpersLocator() {
        val point = ApplicationManager.getApplication().extensionArea
            .getExtensionPoint<PythonHelpersLocator>("com.jetbrains.python.pythonHelpersLocator")
        val extra = point.extensionList.filter { "PythonHelpersLocatorDefault" !in it.javaClass.name }
        extra.forEach { point.unregisterExtension(it) }
    }
}
