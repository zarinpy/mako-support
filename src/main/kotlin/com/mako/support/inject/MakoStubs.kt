package com.mako.support.inject

/**
 * Runtime types pasted into render-scope fragments.
 * The classes ship under `mako-stubs/` and are inlined because the Python plugin
 * does not import a module that exists only as a plugin resource.
 */
object MakoStubs {
    val preamble: String by lazy { load() }

    private fun load(): String {
        val classes = STUB_FILES.joinToString("\n\n") { name ->
            val stream = MakoStubs::class.java.getResourceAsStream("/mako-stubs/$name")
                ?: error("Missing Mako stub /$name")
            stream.bufferedReader().use { it.readText().trim() }
        }
        return classes + "\n\n" + RENDER_NAMES
    }

    private val STUB_FILES = listOf(
        "context.py",
        "namespace.py",
        "loop_context.py",
        "undefined.py",
    )

    private val RENDER_NAMES = """
        class Callable:
            pass

        context: Context
        UNDEFINED: Undefined
        STOP_RENDERING: str
        local: Namespace
        self: Namespace
        parent: Namespace
        next: Namespace
        caller: Namespace
        capture: Callable
        pageargs: dict
    """.trimIndent()
}
