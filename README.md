# Mako

Editor support for the [Mako](https://www.makotemplates.org/) template language on the IntelliJ Platform.

The plugin targets [Mako 1.4.3](https://docs.makotemplates.org/en/latest/). A Mako file is Mako structure, Python inside each code island, and a data language (HTML by default) in the surrounding text. The plugin will own the Mako structure and reuse the platform's Python and HTML support for the other two.

| | |
| --- | --- |
| Plugin id | `com.mako.support` |
| Name | Mako |
| Version | 0.0.1 |
| Language | Kotlin |
| JDK | 21 |
| Default IDE | IntelliJ IDEA 2025.3.6.1 |
| PyCharm sandbox | 2025.3.6.1 |

## Status

Phase 0 is done. The plugin loads in the IDE sandbox, and the template sample tool window, service, and startup activity are gone. File types, highlighting, Python injection, and navigation are not implemented yet.

The work is specified in:

- [plans/mako-full-support.md](plans/mako-full-support.md) — product scope for the full language plugin
- [plans/phases-1-5.md](plans/phases-1-5.md) — implementation order for the lexer through authoring

When those phases land, `.mako` and `.mak` files get highlighting, structural checks, HTML around Mako tags, Python completion inside expressions and blocks, and navigation across `<%inherit>`, `<%include>`, and `<%namespace>`.

## Requirements

- JDK 21
- The Gradle wrapper in this repository (`./gradlew`)

`./gradlew check` runs against IntelliJ IDEA 2025.3.6.1. The Python plugin is not required until Phase 3. `runPyCharm` downloads PyCharm 2025.3.6.1 and opens a sandbox with this plugin installed.

## Develop

```bash
./gradlew check          # compile and run tests
./gradlew runIde         # open IntelliJ IDEA with the plugin
./gradlew runPyCharm     # open PyCharm with the plugin
./gradlew buildPlugin    # write build/distributions/*.zip
./gradlew verifyPlugin   # run the IntelliJ Plugin Verifier
```

The same tasks are available as run configurations under `.run/`: **Run Plugin**, **Run Plugin on PyCharm**, **Run Tests**, and **Run Verifications**.

Install a built zip from **Settings | Plugins | Install Plugin from Disk…**.

## Layout

```
src/main/resources/META-INF/plugin.xml   plugin id, name, and dependencies
src/test/kotlin/com/mako/support/        plugin load test
plans/                                   feature and phase plans
build.gradle.kts                         IDEA target and the runPyCharm task
```

Language code will live in `src/main/kotlin/com/mako/support/` as the phases are implemented.

## License

[Apache License 2.0](LICENSE).

The project scaffold comes from the [IntelliJ Platform Plugin Template](https://github.com/JetBrains/intellij-platform-plugin-template).
