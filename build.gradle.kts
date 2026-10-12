import org.jetbrains.intellij.platform.gradle.IntelliJPlatformType
import org.jetbrains.intellij.platform.gradle.TestFrameworkType

plugins {
    id("org.jetbrains.kotlin.jvm")
    id("org.jetbrains.intellij.platform")
    id("org.jetbrains.changelog")
}

dependencies {
    testImplementation("junit:junit:4.13.2")

    // IntelliJ Platform Gradle Plugin Dependencies Extension - read more: https://plugins.jetbrains.com/docs/intellij/tools-intellij-platform-gradle-plugin-dependencies-extension.html
    intellijPlatform {
        // Phase 3 tests and runIde need the Python plugin. PyCharm 2025.3.6.1
        // bundles it; the plugin id inside that layout is PythonCore
        // (module com.intellij.modules.python).
        pycharm("2025.3.6.1")
        bundledModule("intellij.spellchecker")
        // Confirmed from the PyCharm 2025.3.6.1 layout: plugins/python-ce publishes
        // id PythonCore and module com.intellij.modules.python.
        bundledPlugin("PythonCore")
        testFramework(TestFrameworkType.Platform)
    }
}

// Separate PyCharm run task kept so the existing run configuration still has a target.
intellijPlatformTesting {
    runIde {
        register("runPyCharm") {
            type = IntelliJPlatformType.PyCharm
            version = "2025.3.6.1"
        }
    }
}
