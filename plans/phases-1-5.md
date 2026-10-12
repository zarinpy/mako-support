# Phases 1–5: implement Mako editing

Execution plan for the editor. Product scope, settings names, and the phase 6–7 work stay in [mako-full-support.md](mako-full-support.md). This file is the order of work for the lexer through authoring.

Language behavior matches Mako 1.4.3 `mako/lexer.py` and `mako/parsetree.py` (tag `rel_1_4_3`). Where the overview and the lexer disagree, this file follows the lexer.

## Status (2026-10-12)

| Phase | State | Where it landed |
| --- | --- | --- |
| 0 | Done | `647d3a1` (`init`) on `main`. The Python `<depends>` and the PyCharm Gradle target arrived with Phase 3, not here. |
| 1 | Partial | Language, file type (no icon), lexer, flat parser and PSI, and the syntax highlighter shipped inside `33dd387` (merged to `main` as `3a1da27`). Icon, color settings, brace matcher, commenter, quote handler, structural inspections, nested control and tag PSI, stub element types, and the oracle are still open. |
| 2 | Done | `33dd387`, merged to `main` as `3a1da27` (pull request #1). |
| 3 | Done | `7f11572` on `feature/phase-3-python-injection`. `./gradlew check --offline`: 26 tests, 0 failures. |
| 4 | Not started | |
| 5 | Not started | |

Do not redo a done slice. A short note under each finished or partial phase records what the code does when that differs from the spec text. The spec text stays so the unfinished slices still have their requirements. Next editor work is the open part of Phase 1, then Phase 4. Phases 6 and 7 stay in the full plan.

## Prerequisite

Phase 0 is done (`647d3a1`). Phase 3 moved the Gradle target onto PyCharm, so later slices compile and test on that classpath:

- Plugin id `com.mako.support`, name `Mako`, sources under `src/main/kotlin/com/mako/support/`.
- Sample tool window, startup activity, and project service removed, including their `plugin.xml` entries and tests.
- `plugin.xml` depends on `com.intellij.modules.platform`, `com.intellij.modules.xml`, the spellchecker module, and `com.intellij.modules.python`.
- `build.gradle.kts` uses `pycharm("2025.3.6.1")`, `bundledModule("intellij.spellchecker")`, and `bundledPlugin("PythonCore")`. `runIde` is PyCharm. The extra `runPyCharm` task stays.
- `./gradlew check` is the gate. After Phase 3 it is 26 tests and 0 failures (`MakoDataLanguageTest` 6, `MakoFileTypeTest` 2, `MakoLexerTest` 4, `MakoPluginTest` 1, `MakoPythonInjectionTest` 11, `MakoSpellcheckingTest` 2).

`./gradlew check` stays green at the end of every slice below. A slice does not start until the previous slice's tests pass.

## Lexer facts the implementation uses

These are settled. Do not re-litigate them while coding.

| Topic | 1.4.3 behavior |
| --- | --- |
| `$` | Only `${` starts an expression. `$$` is two text characters. There is no dollar-escape rule. |
| `%%` | At the start of a line, after optional whitespace, `%%` is literal text. The lexer keeps one `%` plus any further `%`. A `%` later in the line is text. |
| `##` | A line comment when it is the first non-space on the line. The coding cookie `#...coding...` at the top of the file is consumed before the main scan. The editor still shows that line as a comment and records the encoding. Default encoding is UTF-8. |
| Newline filter | A trailing `\` before a newline is dropped from the text node. The editor keeps a `NEWLINE_ESCAPE` token so the backslash stays visible and the formatter does not delete it. |
| Control openers | `if`, `for`, `while`, `try`, `with` push a block. |
| Interior keywords | `if` accepts `elif` and `else`. `for` accepts `else`. `try` accepts `except` and `finally`. `while` and `with` accept none. |
| `% else` under `% try` | A compile error. `try` has no `else`. |
| `% def` | Not an opener. It is a one-line statement, same as `% x = 1`. `% enddef` is an error ("no starting keyword"). The inspection that suggests `<%def>` still applies to a one-line `% def`. |
| Closers | `% end<keyword>` must name the open primary (`endif`, `endfor`, `endwhile`, `endtry`, `endwith`). |
| Other `%` lines | `% import os` and `% x = 1` are one-line statements. The first word is the keyword. A line that does not match `(end)?(\w+)\s*(.*)` is an invalid control line. |
| Control continuations | A control line may continue with `\` plus a newline. |
| `<%doc>` | A single comment from `<%doc>` through `</%doc>`. The body is not scanned. An unclosed `<%doc>` is an error. |
| `<%text>` | The body is raw text through `</%text>`. Mako syntax inside is text. |
| `<%` and `<%!` | Python block through `%>`. Strings and `#` comments do not end the block. `<%!` is module-level. |
| `${}` | Expression through `}` or `\|`, tracking `()`, `[]`, and `{}` and skipping strings and `#` comments. Text after `\|` is the filter list, through `}`. |
| Tags | `<%keyword attrs />` or `<%keyword attrs> ... </%keyword>`. Keyword is `[\w.:]+`. A keyword containing `:` is a custom tag `ns:def`. Any other unknown keyword is "no such tag". |
| Closing tags | `</%keyword>` must match the open keyword, including the `ns:def` form. |
| Attribute comma | A comma between attributes is accepted by the tag regex and is a warning in the editor. |

Tag attributes, from `parsetree.py`. "Expr" means the value may contain `${...}`. "Literal" means `${...}` in the value is a compile error.

| Tag | Required | Expr attributes | Literal attributes |
| --- | --- | --- | --- |
| `page` | | `args`, `expression_filter`, `enable_loop`, `cached`, every `cache_*` | |
| `include` | `file` | `file`, `import`, `args` | |
| `inherit` | `file` | `file` | |
| `namespace` | `name` or `import` (at least one) | `file` | `name`, `import`, `module`, `inheritable` |
| `def` | `name`, and `name` must contain parentheses | `buffered`, `cached`, every `cache_*` | `name`, `filter`, `decorator` |
| `block` | | `buffered`, `cached`, `args`, every `cache_*` | `name`, `filter`, `decorator` |
| `call` | `expr` | `args` | `expr` |
| `text` | | | `filter` |
| `ns:def` | | every attribute, including `args` | |

Extra rules the parser enforces:

- `namespace` rejects both `file` and `module`.
- `block` `name`, when present, is a bare identifier. `args` is legal only on a named block.
- Only the first `page` tag is used. A second one is a warning, not a second signature.
- `doc` is not a tag. The comment matcher owns `<%doc>`.

Built-in filters, subtracted from unresolved names the way `mako.filters.DEFAULT_ESCAPES` does: `h`, `u`, `x`, `trim`, `entity`, `str`, `unicode`, `decode`, `n`. `decode.utf8` is the `decode` filter plus an encoding suffix.

## Package layout for these phases

```
src/main/kotlin/com/mako/support/
  lang/         MakoLanguage, file type, brace matcher, commenter, quote handler
  lexer/        MakoLexer, MakoTokenTypes
  parser/       MakoParserDefinition, MakoParser, element types
  psi/          PSI classes and small helpers
  highlight/    highlighter, color page, annotator
  inspections/  one inspection class per diagnostic
  template/     view provider and template-data element type          (phase 2)
  inject/       injector, hosts, preamble                             (phase 3)
  settings/     project settings                                      (phase 4)
  index/        stub index                                            (phase 4)
  resolve/      references, rename, find usages                       (phase 4)
  completion/   contributor                                           (phase 5)
  editor/       typed handler, enter handler, live templates, surround
  structure/    folding, structure view, breadcrumbs, line markers
src/main/resources/
  META-INF/plugin.xml
  mako-stubs/   Context.pyi Namespace.pyi LoopContext.pyi Undefined.pyi
src/test/kotlin/com/mako/support/
src/test/testData/mako/
  oracle/       .mako sources and committed .json trees
  highlight/ inspection/ completion/ resolve/ rename/ typing/
tools/mako_oracle.py
```

Packages on disk after Phase 3: `lang/`, `lexer/`, `parser/`, `psi/`, `highlight/` (highlighter only), `template/`, `inject/`, `spellcheck/`. The stub resources that shipped are `src/main/resources/mako-stubs/*.py` (`context.py`, `namespace.py`, `loop_context.py`, `undefined.py`). The sketch above says `.pyi`; the files are `.py` because their text is pasted into the injected fragment. Not created yet: `inspections/`, `settings/`, `index/`, `resolve/`, `completion/`, `editor/`, `structure/`, `MakoIcons`, `src/test/testData/mako/`, `tools/mako_oracle.py`.

`plugin.xml` gains an extension only in the slice that implements it. No empty registrations.

## Shared test harness

Not built yet. Tests today extend `BasePlatformTestCase` directly: `MakoLexerTest`, `MakoFileTypeTest`, `MakoDataLanguageTest`, `MakoSpellcheckingTest`, `MakoPythonInjectionTest`, and `MakoPluginTest`. There is no oracle corpus. Until this harness lands, a later slice stays green by keeping those tests passing.

When this slice is built, one base class, `MakoTestCase`, extends `BasePlatformTestCase` and points the fixture at `src/test/testData/mako`. Helpers:

- `configure(path)` loads a `.mako` file.
- `assertPsiMatchesOracle(name)` compares the PSI dump with `oracle/<name>.json`.
- Later slices add completion, resolve, rename, and typing helpers on the same class.

Oracle JSON is committed. CI does not need CPython. Regenerate with `tools/mako_oracle.py` when the corpus or the pinned Mako version changes.

The script installs Mako `1.4.3`, runs `Lexer(text).parse()`, and writes one object per file:

```json
{
  "encoding": "utf-8",
  "nodes": [
    {"kind": "ControlLine", "keyword": "if", "isend": false, "text": "if x==5:", "line": 1, "pos": 1},
    {"kind": "Expression", "text": "x", "escapes": ["h", "trim"], "line": 2, "pos": 5}
  ],
  "error": null
}
```

Files that Mako rejects set `error` to the exception message and leave `nodes` empty. The plugin test asserts the same error, at the same start line. Newline-escape tokens and the coding-cookie comment are plugin-only nodes; the comparator skips them.

## Phase 1 — Lexer, parser, highlighting — partial

Shipped inside `33dd387`: language, file type, lexer, a flat parser and PSI, and the syntax highlighter. HTML on the Mako side is still one text token. Python injection is Phase 3 and is already done, so new Phase 1 work has to keep those injections green. The data-language view provider from Phase 2 is also already done.

### 1.1 Language and file type — done except the icon

`MakoLanguage` and `MakoFileType` (extensions `mako` and `mak`) are registered. `MakoFileTypeTest` creates `a.mako` and `a.mak` and asserts the language. A `.html` file stays HTML.

Still open: `MakoIcons` and a file-type icon under `src/main/resources/icons/`.

### 1.2 Lexer — done

`MakoLexer` is a hand-written `LexerBase`. `MakoLexerTest` (4 tests) covers the lexer-facts table, a nested `${f(a, {"b": 1}) | h}`, a filter bar inside a string, and a `\`-continued control line. Do not rewrite the lexer to finish the open Phase 1 slices. States:

| State | Ends on |
| --- | --- |
| `TEXT` | `${`, `<%`, `</%`, a control line, `%%`, a newline escape, or EOF |
| `EXPRESSION` | `\|` at nesting level 0, or `}` at nesting level 0 |
| `FILTER` | `}` at nesting level 0 |
| `TAG` | `>` or `/>` |
| `PYTHON_BLOCK` | `%>` outside a string and outside a `#` comment |
| `RAW` | the closing tag already decided by the opener (`</%text>` or `</%doc>`) |
| `CONTROL` | end of the logical line, after `\` continuations |
| `LINE_COMMENT` | end of line |

Nesting inside `EXPRESSION` and `FILTER` counts `()`, `[]`, `{}` and skips quoted strings (including triple quotes) and `#` comments, matching `parse_until_text(watch_nesting=True)`. `PYTHON_BLOCK` skips strings and `#` comments and does not track brackets.

Scan order at each `TEXT` position matches `Lexer.parse`: end, expression, control line or `##`, `<%doc>`, tag start, tag end, python block, `%%`, text. Tag start that sees `text` switches to `RAW` until `</%text>`.

Tokens live in `MakoTokenTypes`: text, expression braces, expression body, filter bar, filter body, tag open, tag keyword, attribute name, attribute value, tag close, python delimiters, module marker `!`, control percent, control body, comment, newline escape, bad character.

### 1.3 Parser and PSI — flat tree shipped; nested tree, stubs, and oracle still open

Shipped in `33dd387`: `MakoParserDefinition`, a recursive-descent `MakoParser`, and PSI for a flat file. Children of `MakoFile` are text, expressions, python blocks, module blocks, one control statement per `%` line (openers, interiors, and closers are the same element), tags, attributes, and comments. A tag body is not nested under the tag. There is no `MakoControlBlock`, no per-tag class (`MakoPageTag`, `MakoDef`, and the rest), and no `IStubElementType`. An attribute value is one token. `${...}` holes inside it are found by `MakoPythonText.expressionHoles`, not by child expressions. `<div class="${cls}">` is template text plus a `MakoExpression`, not a Mako attribute.

Phase 3's `MakoInjectionModel` walks that flat tree with a stack. If this slice later builds the nested tree below, the same change has to keep injection, HTML data ranges, and the existing tests green.

The tree this slice still owes:

```
MakoFile
  MakoCodingComment?          kept for the editor; Mako itself drops it
  MakoContent*
```

`MakoContent` is one of:

- `MakoTemplateText` — one or more text tokens, plus any newline-escape tokens between them
- `MakoExpression` — body leaf, optional `MakoFilterList`
- `MakoPythonBlock` / `MakoModuleBlock` — body leaf
- `MakoLineComment` / `MakoDocComment`
- `MakoTextTag` — body is a raw leaf
- `MakoControlStatement` — one-line `%` that is not an opener, interior, or closer
- `MakoControlBlock` — opener, body, closer
  - body contains content and `MakoControlInterior` (`elif`, `else`, `except`, `finally`)
- tag elements: `MakoPageTag`, `MakoIncludeTag`, `MakoInheritTag`, `MakoNamespaceTag`, `MakoDef`, `MakoBlock`, `MakoCall`, `MakoCustomTag`

A paired tag's children are the content between the open and close tags. A self-closing tag has an empty body. Attributes are `MakoAttribute` children of the open tag, each with a name and a value. A value that contains `${...}` has `MakoExpression` children for those parts and text for the rest.

The parser builds a tree for broken files. It does not throw out of `parse`. Unclosed nodes get `PsiErrorElement` children. The structural inspections in slice 1.5 read those errors and the recovered tree. The flat parser already recovers a tree; it does not yet attach those error children for broken nesting, and slice 1.5 is what reports them.

Element types that carry an index later (`MakoDef`, `MakoBlock`, `MakoNamespaceTag`, `MakoIncludeTag`, `MakoInheritTag`, `MakoPageTag`, `MakoFile`) were **not** created as `IStubElementType` stubs. Phase 4 introduces them. See slice 4.2.

Test, still open: `assertPsiMatchesOracle` on the corpus. Add files for nested defs, a block inside a for, a custom tag, a comma between attributes, an unclosed def, a mismatched `endfor`, `% else` under `% try`, `% enddef`, an unknown tag, `namespace` with both `file` and `module`, a def name without parentheses, and `args` on an anonymous block.

### 1.4 Highlighting, braces, comments — highlighter done; the rest is open

`MakoSyntaxHighlighter`, `MakoSyntaxHighlighterFactory`, and `MakoEditorHighlighter` (`LayeredLexerEditorHighlighter`) are registered. `MakoTokenTypes.TEXT` is painted by the template-data highlighter. Text attribute keys are `TextAttributesKey.EMPTY_ARRAY`.

The highlighter already maps tokens to keys: text (default), expression, filter, tag keyword, attribute name, attribute value, control marker, control keyword, comment, module block, python block, newline escape, bad character.

Still open:

`MakoColorSettingsPage` registers the **Mako** scheme page with a sample that contains one of each key.

`MakoBraceMatcher` pairs `${` / `}`, `<%` / `%>`, `<%!` / `%>`, a tag open with its close, and a control opener with its `end*` line. Interior keywords point at the opener.

`MakoCommenter` line-comments with `## ` and block-comments with `<%doc>` / `</%doc>`.

`MakoQuoteHandler` inserts the matching quote inside attribute values and does not treat `${` as a string delimiter.

Tests: one highlighting file per token key. Brace tests open a nested `if` inside a `for` and a tag inside a def. Commenter tests toggle a line and a selection.

### 1.5 Structural inspections — not started

Each inspection is a `LocalInspectionTool` on `MakoLanguage`. Quick fixes are `LocalQuickFix` instances that edit the PSI. None of these classes exist.

| Inspection | Severity | Quick fix |
| --- | --- | --- |
| Unclosed tag, `${`, or `<%` | error | insert the closer when the opener is known |
| Control closer missing, unknown, or mismatched | error | insert or replace the closer |
| Interior keyword with no legal opener (`% else` under `% try`, `% elif` under `% for`) | error | |
| Invalid control line | error | |
| Unknown tag keyword | error | |
| `def` missing `name`, or `name` without parentheses | error | |
| `include` or `inherit` missing `file` | error | insert `file=""` |
| `call` missing `expr` | error | insert `expr=""` |
| `namespace` missing both `name` and `import` | error | |
| `namespace` with both `file` and `module` | error | |
| Named block duplicated, nested in a def or call, or named like a top-level def | error | |
| `args` on an anonymous block | error | |
| Second `page` tag | warning | |
| Comma between attributes | warning | delete the comma |
| One-line `% def` | weak warning | replace with a `<%def>` skeleton |
| Literal attribute containing `${` | error | |

Attribute-name legality (unknown attribute on a known tag) is an error in this slice. Filter-name checking waits for Phase 4, because a filter may be a function defined in another file or in `<%! %>`.

Tests: one highlighting file per row, with the quick fix asserted where the table lists one.

### Phase 1 exit — not met

`./gradlew check` passes. Highlighting distinguishes tags, expressions, filters, control lines, comments, and both Python block kinds. The rest of the exit is open: broken nesting is not an inspection, there is no color-settings page, and there is no oracle. `.html` is unchanged.

## Phase 2 — Data language — done

Done in `33dd387`, merged to `main` as `3a1da27`. `MakoDataLanguageTest` (6) and `MakoSpellcheckingTest` (2). Do not rebuild the view provider or the spellcheck strategy.

What shipped, where it differs from the slices below:

- `MakoFileViewProvider` extends `MultiplePsiFilesPerDocumentFileViewProvider` and implements `ConfigurableTemplateLanguageFileViewProvider`. Registered with `lang.fileViewProviderFactory`. The base language is Mako. The template-data language comes from `TemplateDataLanguageMappings` and defaults to HTML. Mapping the file to plain text uses `PlainTextLanguage`.
- `MakoTemplateDataElementType` re-parses template text. `MakoParser` does not parse HTML.
- On 2025.3 the HTML tag-name leaf is `XmlTokenType.XML_NAME` inside an `XmlTag`.
- `MakoSpellcheckingStrategy` is registered with `<spellchecker.support language="Mako" .../>`. `build.gradle.kts` bundles `intellij.spellchecker`. The inspection class `com.intellij.grazie.spellcheck.GrazieSpellCheckingInspection` is loaded by name. There is no compile dependency on Grazie.

### 2.1 View provider

`MakoLanguage` implements `TemplateLanguage`.

`MakoFileViewProvider` extends `TemplateLanguageFileViewProvider` and is registered with `lang.fileViewProviderFactory`. The base language is Mako. The template-data language is the language from `TemplateDataLanguageMappings`, default `HTMLLanguage.INSTANCE`.

`MakoTemplateDataElementType` extends `TemplateDataElementType`. Every `MakoTemplateText` range is re-parsed by the data language. Mako nodes show up in the data-language tree as outer-language elements, which is what keeps HTML tag matching working across an expression.

`MakoTemplateText` from Phase 1 stays the Mako-side node. Its text range is the range the data element type re-parses. Do not parse HTML inside `MakoParser`.

### 2.2 HTML behavior

Tests, all on one fixture file `<div class="${cls}"><span></div>`:

- The HTML highlighter colors the tags.
- Completing inside `<di` offers `div`.
- The unmatched `</div>` / `<span>` warning from the HTML inspections still fires.
- Typing `>` after `<div` still runs the HTML tag-closer outside the expression.
- `${cls}` is a Mako expression, and HTML completion does not fire inside it.

A second test sets the file's template data language to plain text and asserts that `<div>` is not highlighted as HTML.

### 2.3 Spellcheck

Register a `SpellcheckingStrategy` for `MakoLanguage`. Tokenize comments and template text. Skip expression bodies, filter lists, Python blocks, tag keywords, attribute names, and control bodies. Attribute values are spelled when they contain no `${`.

Test: a misspelled word in text and in a `##` comment is flagged. The same letters inside `${}` and `<% %>` are not.

### Phase 2 exit — met

`<div class="${cls}">` is HTML around the expression and Mako inside it. A file whose data language is plain text does not run HTML inspections. There is no Phase 1 oracle suite. The lexer, file-type, data-language, and spellcheck tests pass.

## Phase 3 — Python injection — done

Done in `7f11572` on `feature/phase-3-python-injection`. `MakoPythonInjectionTest` has 11 tests. `./gradlew check --offline` on that commit: 26 tests, 0 failures. Do not rebuild the injector.

The Gradle target is already switched. `pycharm("2025.3.6.1")` alone did not put Python classes on the compile classpath, so `build.gradle.kts` sets both the PyCharm target and the plugin id confirmed from `plugins/python-ce` in that layout:

```kotlin
intellijPlatform {
    pycharm("2025.3.6.1")
    bundledModule("intellij.spellchecker")
    bundledPlugin("PythonCore")
    testFramework(TestFrameworkType.Platform)
}
```

`runIde` runs PyCharm. The separate `runPyCharm` task remains. `plugin.xml` depends on `com.intellij.modules.python`. Phase 1 and 2 tests pass on this classpath.

Injection uses the public `MultiHostInjector`. The extension element is `<multiHostInjector>` under `defaultExtensionNs="com.intellij"`. The interface is `com.intellij.lang.injection.MultiHostInjector`. No Python-plugin template-language API in this phase. That API is Phase 6.

Shipped behavior that later slices must keep. Where a row in the tables below and this list disagree, this list is what the code does.

- Hosts are `MakoExpression`, `MakoPythonBlock`, `MakoModuleBlock`, `MakoControlStatement`, and `MakoAttribute`, through `MakoInjectionHost`. The host range is the source range. Prefix and suffix are not in the file. Tests find injected PSI with `InjectedLanguageManager`.
- Stub resources are `mako-stubs/context.py`, `namespace.py`, `loop_context.py`, and `undefined.py` (that last file also holds `STOP_RENDERING`). Their text is pasted into the fragment prefix. The `from mako_support_stubs import ...` line is not used, because the Python plugin will not import a module that exists only as a plugin resource. The header also contains a small `Callable` class so `capture: Callable` is valid.
- Module-block prefix is the text of every earlier `MakoModuleBlock` only. It does not include `context`, `self`, `pageargs`, or the other render names. The test fixture is `<%!\ncontext\n%>`, not `${context}` inside a module block.
- Render prefix order: all module-block text, then the header, then page-argument names as `name = UNDEFINED`, then a function stub for every top-level `<%def>` and named `<%block>`. Nested defs are added only when the host is inside that def. Anonymous blocks add nothing. Top-level defs are visible above their definition.
- Inside a def, the prefix opens `def name(params):` and declares extra locals as `x = None`. Expressions are wrapped as `_ = (...)`, so indentation does not matter. A python block inside a def is one injected place per non-empty line. Assignment targets come from a scan in `MakoPythonText`, not from `PyElementVisitor`.
- Control closers (`endfor`, `endif`, `endwhile`, `endtry`, `endwith`, `enddef`) are not injected. `elif` and `else` get an `if True: pass` prelude, `except` gets `try: pass`, and `finally` gets `try` / `except` / `pass`. Compound statements get a `pass` suffix so the highlighter is not red on an unfinished suite.
- A `def` `name` value injects `def <value>: pass` with no render preamble. `page` and named `block` `args` inject `def _page(<args>): pass` with no render preamble. `call` `expr` and `decorator` inject `_ = (<expr>)` with the render preamble. Each `${...}` inside an expr attribute does the same, including custom filters inside that hole.
- Built-in filters are not injected: `h`, `u`, `x`, `trim`, `entity`, `str`, `unicode`, `n`, and `decode.<encoding>` (for example `decode.utf8`). `myfilters.tagfilter` is injected.
- `loop: LoopContext` is inserted only for a host lexically inside a `% for` (after the `for` line, until `endfor` or the enclosing tag), and only when the first `<%page>` `enable_loop` value is not `false` (ignore case) or `0`. Default is on. The class `LoopContext` is always in the preamble. The test asserts that the assignment line `loop: LoopContext` is absent, not that the class name is absent.
- `strict_undefined` is off. Unknown names stay ordinary Python names. Do not assign them to `UNDEFINED`. `caller` is a `Namespace`, and `__getattr__` keeps unknown def names legal. `pageargs: dict` is always in this phase's header. Phase 4 owns the warning and error mode, the settings page, the index, imports from project settings, the `**kwargs` restriction on `pageargs`, and the inheritance attributes of `self`, `parent`, and `next`.
- `MakoPythonTestLayout` lives in the test source set. `MakoPlatformTestCase.setUp` points `idea.python.helpers.path` at the community helpers beside the PyCharm layout and unregisters every `pythonHelpersLocator` whose class is not `PythonHelpersLocatorDefault`. It is not registered in `plugin.xml`. The pro locator and `ApplicationInitializedListener` are internal API, and shipping them fails `verifyPlugin`. The pro locator throws in the test sandbox because plugin jars live under `lib/modules` rather than `lib`, and a failed `PyTypeShed` init then fails every later test in that JVM.

### 3.1 Hosts

`MakoPythonInjector` registers one host per range:

| PSI | Python fragment |
| --- | --- |
| expression body | `_ = (<body>)` |
| each custom filter after the builtins are stripped | `_ = (<filter>)` |
| `MakoPythonBlock` body | statements, prefix empty, suffix empty |
| `MakoModuleBlock` body | see 3.2; all module blocks in the file share one generated prefix |
| control-line body, including interiors | one statement |
| `def` `name` value | `def <value>: pass` when the value has no `${` |
| `page` and named `block` `args` | `def _page(<args>): pass` |
| `call` `expr` | `_ = (<expr>)` |
| `decorator` value | `_ = (<value>)` |
| each `${...}` inside an expr attribute | `_ = (<expr>)` |

Built-in filter names are not injected. `decode.utf8` is not injected. `myfilters.tagfilter` is injected.

The host range is the source range inside the Mako file. Prefix and suffix are not part of the file. `InjectedLanguageManager` is how tests find the injected `PyElement`.

### 3.2 Stubs and preamble

Ship `src/main/resources/mako-stubs/` with four stub modules, typed to the 1.4.3 members listed in the full plan:

- `Context` — `lookup`, `kwargs`, `keys`, `get`, `write`, `writer`, `namespaces`, `__getitem__`
- `Namespace` — `attr`, `cache`, `context`, `filename`, `module`, `uri`, `template`, `get_namespace`, `get_template`, `get_cached`, `include_file`, plus `__getattr__` so unknown def names stay legal until Phase 4 replaces them with real stubs
- `LoopContext` — `index`, `reverse_index`, `first`, `last`, `even`, `odd`, `parent`, `cycle`
- `Undefined` — truthiness false; `__str__` raises `NameError`

`STOP_RENDERING: str = ""` sits next to them.

The injector does not modify the user's SDK. It pastes a short header into the fragment prefix:

```python
from mako_support_stubs import Context, Namespace, LoopContext, Undefined, STOP_RENDERING
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
```

`mako_support_stubs` is that header's synthetic module, whose text is the four stub files concatenated. Register it as an injected-language `additional file` through the prefix only. If the Python plugin ignores a module that is not on disk, write the stubs into the fragment prefix directly and drop the import. Either way, no SDK path change.

Module-block prefix is different. Concatenate the text of every `MakoModuleBlock` above the current one, in source order, then the current block. Do not paste `context`, `self`, `pageargs`, or the other render names.

Render-block prefix is the module-block text, then the header above, then the page-argument names as assignments, then a function stub for every top-level `<%def>` and named `<%block>` in the file. Nested defs are added only when the host is inside that def.

### 3.3 Scope rules

- `loop` is inserted into the prefix only for hosts lexically inside a `% for` block, and only when the file does not set `enable_loop` false. Until Phase 4 settings exist, `enable_loop` comes from the first `<%page>` attribute, defaulting to true.
- A def that assigns a name treats that name as local for the whole def. Collect assignment targets with the Python plugin's `PyElementVisitor` on the injected block, or, if the injected PSI is not ready while the prefix is being built, with a small scan of assignment statements in `<% %>` and control lines inside the def. The prefix then declares those names as local parameters of the def stub so an earlier `${x}` does not bind to an outer `x`.
- Top-level defs are visible above their definition. Nested defs are visible only inside the parent def.
- Anonymous blocks add nothing to the preamble. They see the enclosing prefix.
- `caller` inside a def is a `Namespace`. Phase 4 narrows its members to the call site. In this phase every `caller.<name>` resolves through `Namespace.__getattr__`.

`strict_undefined` is off. Unknown names are ordinary Python names in the fragment (they resolve to `UNDEFINED` only as a documentation string in the stub). The warning and the error mode are Phase 4, because they need the settings page and the index.

### 3.4 Tests

| Test | Expect |
| --- | --- |
| `${context.write("x")}` | completion contains `write`; the call type-checks |
| `% for a in items:` / `${loop.index}` | completion contains `index`, `first`, `cycle` |
| `% for` with `<%page enable_loop="False"/>` | `loop` is absent from the prefix |
| `<%! import os %>` then `${os.path.sep}` | `os` resolves |
| `${context}` inside `<%! %>` | `context` does not resolve to the stub |
| `<%def name="hello(who)">` / `${hello("a")}` above the def | `hello` resolves, parameter `who` completes inside the def |
| `<%def name="bad(1)">` | the injected signature has a Python syntax error |
| `${x}` before `<% x = 1 %>` inside a def | `x` is the local, not an outer `x` |
| `${expr \| h, trim}` | no Python error on `h` or `trim` |
| `${expr \| myescape}` with `myescape` defined in `<%! %>` | `myescape` resolves |

### Phase 3 exit — met

Python completion and the Python syntax highlighter work on the hosts the tests cover. `context` is absent from module blocks. `loop` exists only inside `% for`. Phase 2 HTML tests pass. There is no Phase 1 oracle suite.

## Phase 4 — Symbols across files

### 4.1 Settings

`MakoProjectSettings` is a project service and `PersistentStateComponent` stored in `.idea/mako.xml` (project file, not the user's config directory).

| Field | Default |
| --- | --- |
| `templateDirectories` | virtual-file URLs; empty means "a top-level `templates` directory if it exists, otherwise the file's own directory" |
| `defaultFilters` | `str` |
| `imports` | empty list of Python statements |
| `enableLoop` | true |
| `strictUndefined` | false |

`MakoSettingsConfigurable` is a project configurable under Languages & Frameworks → Mako. The form edits directories, the filter string, the imports text area, and the two checkboxes. `output encoding` and extra file extensions are not on this form. They belong to Phase 6 and to Settings → Editor → File Types.

Changing a setting drops the injection cache for open Mako files (`FileContentUtil.reparseFiles`).

`enableLoop` false overrides `<%page enable_loop="True"/>` the other way around: the page attribute wins when it is present, and the project setting is the default. That matches `TemplateLookup(enable_loop=False)` plus a per-template `<%page>`.

The `imports` statements are prepended to the module preamble from Phase 3. `defaultFilters` is stored now and read by the filter inspection below. Nothing rewrites template text because of it.

### 4.2 Stub index

Slice 1.3 did not create `IStubElementType`s, and the parser did not add `MakoDef`, `MakoPageTag`, or the other per-tag classes. This slice introduces the stub types. Until the nested tag PSI from the rest of 1.3 exists, read def, block, namespace, include, inherit, and page facts off the flat tag element and its `MakoAttribute` children. Do not look for classes that are not in the tree.

`MakoFileStub` holds:

- top-level def names and their signature strings
- named block names
- page argument names, and whether the signature already has `**kwargs`
- namespace records: local name, `file` literal or null, `module` literal or null, import list, `inheritable`
- inherit URI literal or null
- include URI literals
- module-level assigned names from `<%! %>` (identifiers only; skip anything the Python stub index would not treat as a simple assignment)

Index keys: `mako.def`, `mako.block`, `mako.namespace`, `mako.child` (files whose inherit URI resolves to a given file). The child key is updated by a `FileBasedIndex` that stores inheriting file URLs after URI resolution, so a parent can list its children without walking the project on every gutter paint.

Stub building reads PSI, never the Python plugin. A def signature is the raw `name` attribute string.

### 4.3 URI resolution

`MakoTemplates.adjustUri(uri, currentFile)` ports `TemplateLookup.adjust_uri`:

- A URI starting with `/` is looked up under each template directory, in order, with the leading slash removed.
- Any other URI is joined with the current file's directory relative to the template directory that contains the current file, then looked up in that directory.
- When no template directory contains the current file, resolve a relative URI against the current file's directory only.
- A value that contains `${` does not resolve and does not produce an unresolved-file error.

`module="pkg.mod"` resolves with `PyModuleIndex` / `QualifiedNameFinder` against the project SDK. A missing SDK leaves the reference unresolved and the inspection silent, because the plugin cannot know the module is absent.

### 4.4 References

`MakoReferenceContributor` registers references on these nodes. Every reference implements `PsiPolyVariantReference` only where a `*` import can hit several defs; everything else is a single target.

| Node | Target |
| --- | --- |
| literal `file` on include, inherit, namespace | the file from `adjustUri` |
| literal `module` | the Python module |
| `import="a, b"` names | top-level defs of that namespace's file, or public functions of its module |
| a call-like name in an expression that matches a local def, an import, or a nested def in scope | that def's name attribute |
| `local.<name>`, `self.<name>` | the top-most def or block of that name on this file's inheritance chain (`self`), or this file only (`local`) |
| `parent.<name>` | the same name on the immediate parent's chain |
| `next.<name>` | the top-most child definition when exactly one inheriting file is indexed; otherwise a poly variant of those children |
| `caller.<name>` inside a def | a def declared in a `<%call>` or `<%ns:def>` body that targets this def. Several call sites produce several variants |
| `self.attr.<name>` and `local.attr.<name>` | a module-level assignment in `<%! %>` of this file or, for `self`, the nearest ancestor that assigns it |
| a custom-tag `ns:def` keyword | the namespace binding `ns`, then the def `def` on its file or module |
| a filter name that is not built in | the same resolution as a bare name in an expression |

`Namespace.__getattr__` stays in the stub so a missing def is a Mako unresolved-reference inspection and not a Python "cannot find reference" error on every dynamic call. The injector's prefix, from this phase on, lists real def stubs for the indexed names and leaves `__getattr__` for the rest.

`pageargs` is in the prefix only when the page signature has no `**kwargs`.

Included-file page arguments: `<%include args="...">` is injected as a keyword call. When the included file resolves and has a page signature, the inspection compares the keyword names with that signature. Extra keywords are legal when the target has `**kwargs` or no explicit signature.

### 4.5 Rename and find usages

`MakoFindUsagesProvider` exposes defs, named blocks, and namespace names.

`MakoRenameProcessor` handles three cases:

- Rename of a def or block renames the `name` attribute and every reference from the table, including the def half of a `<%ns:def>` keyword.
- Rename of a namespace's `name` renames the `ns` half of `<%ns:def>` and expression references `ns.`.
- Rename of a Mako file rewrites literal `file` attributes that resolve to it. A root-absolute URI stays root-absolute. A relative URI stays relative to the referencing file. A URI that no longer points at the file after the move is rewritten from `adjustUri` in reverse.

Tests use the fixture's `testRename` and `findUsages`. One fixture project contains `base.mako`, `child.mako` inheriting from it, and `lib.mako` pulled in with `<%namespace>`. Rename `header` in the child and assert the base's `self.header` reference moves with it. Rename `lib.mako` and assert a relative `file` and a root-absolute `file` both still resolve.

### 4.6 Inspections that need the index

| Inspection | Severity | Quick fix |
| --- | --- | --- |
| Literal `file` or `module` does not resolve | error | for a file that exists outside the template roots, add that root |
| `import` name is not a top-level def or a public module function | error | |
| `import="*"` | information | |
| Unknown filter, after builtins, preamble callables, and imports | warning | |
| Unresolved bare name in an expression | warning when `strictUndefined` is off, error when it is on | |
| Assignment to `context`, `UNDEFINED`, or `loop` while loop is enabled | warning | |
| `loop.*` while this file has loop disabled | warning | |
| Block in an included file treated as if it overrode a parent block | warning | the text points at `<%namespace>` plus an explicit call |

The unresolved-name inspection suppresses itself when the Python fragment already resolved the name (a local, a parameter, a preamble import, or a def stub). It is the inspection for names that fell through to "someone might pass this in the context".

Reserved-name assignment is detected from control lines and `<% %>` blocks whose first assignment target is `context`, `UNDEFINED`, or `loop`.

### Phase 4 exit

In a fixture project, `<%inherit>`, `<%include>`, and `<%namespace>` navigate and complete across files. `self`, `parent`, `next`, `caller`, and `self.attr` resolve as the table says. Rename of a def updates a custom tag in another file. Phase 1–3 tests still pass.

## Phase 5 — Authoring

Completion and typing only. No new resolution rules.

### 5.1 Completion

`MakoCompletionContributor` on `MakoLanguage`. Python completion inside hosts stays the Python plugin's. This contributor handles Mako tokens.

| Where | Offers | Inserts |
| --- | --- | --- |
| after `<%` | `page`, `include`, `inherit`, `namespace`, `def`, `block`, `call`, `text`, `doc` | a skeleton: required attribute, caret in the quotes, `/>` for empty tags, a closing tag for tags with a body |
| after `<%ns:` | defs exported by namespace `ns` | the def name and `>` |
| attribute position of a known tag | that tag's attributes that are not already present, plus `cache_*` suggestions `cache_key`, `cache_timeout`, `cache_type`, `cache_dir`, `cache_url`, `cache_region` on page, def, and block | `name=""` with the caret between the quotes |
| a filter list | the built-in filter names, `decode.`, and callables in scope | the name |
| `cache_type` value | `memory`, `file`, `dbm`, `ext:memcached`, `memcached` | the word |
| `enable_loop`, `cached`, `buffered`, `inheritable` value | `True`, `False` | the word |
| a literal `file` value | files under the template roots, using `/` for root-absolute | the URI |
| a `module` value | Python modules from the SDK | the dotted name |
| a control line after `%` | `if`, `for`, `while`, `try`, `with`, `elif`, `else`, `except`, `finally`, and the matching `end*` if an opener is unclosed | the keyword and, for an opener, a trailing `:`. An opener also inserts the closer on the following line when the closer is absent |
| an `import` value | the target's top-level defs, and `*` | the name |

Custom-tag attribute names are not completed. They are defined by the callee.

Tests call `completeBasic` at a marked caret for each row.

### 5.2 Typing

`MakoTypedHandler` on `>` and `%` and `:`.

- `>` inside a tag open inserts the closing tag when the tag takes a body and the closer is not already present. Self-closing tags are unchanged.
- `%` typed as the first non-space on a line opens the control-keyword lookup from the table above.
- `:` typed at the end of a control opener inserts the closer line when it is missing, then moves the caret to the body line, indented one level past the opener.

`MakoEnterHandler` delegates to the Python enter handler when the caret is inside a Python block or an expression. Elsewhere, inside a control block or a paired tag, it indents to the body indent. A newline inside `<%text>` or `<%doc>` keeps column 0 of the previous line's content.

Tests are `myFixture.type` sequences: type `<%def>`, accept the lookup, assert the skeleton and the caret; type `% if True:`, assert `% endif` was inserted; press Enter inside `<% %>`, assert the Python indent.

### 5.3 Folding, structure, breadcrumbs

`MakoFoldingBuilder` folds defs, blocks, namespaces with a body, Python blocks, doc comments, and control blocks of more than two lines. The placeholder is the tag keyword plus the name, or the control keyword plus the condition text trimmed to 40 characters.

`MakoStructureViewFactory` shows, in order: the inherit file, includes, namespaces, the page signature, top-level defs, named blocks. Nested defs appear under their parent def.

`MakoBreadcrumbsProvider` shows the stack of paired tags and control blocks from the caret outward. A def breadcrumb uses the signature. A control breadcrumb uses the keyword.

Tests: folding descriptors on a sample file, structure-view snapshot, breadcrumb list with the caret inside a nested block.

### 5.4 Live templates and surround

Default live templates, registered from `src/main/resources/liveTemplates/Mako.xml`, context `Mako`:

| Abbreviation | Expands to |
| --- | --- |
| `def` | a `<%def name="name()">` skeleton |
| `block` | a named `<%block>` |
| `inherit` | `<%inherit file=""/>` |
| `include` | `<%include file=""/>` |
| `namespace` | `<%namespace name="" file=""/>` |
| `for` | `% for` / `% endfor` |
| `if` | `% if` / `% endif` |
| `call` | `<%call expr="">` |

`MakoSurroundDescriptor` surrounds a selection with `% if`, `% for`, `<%def>`, `<%block>`, and `<%text>`. The selection becomes the body. The caret lands on the condition or the name.

Tests expand `if` and surround a selection with `<%text>`.

### 5.5 Formatter

`MakoFormattingModelBuilder` is deliberately small.

- Injected Python uses the Python formatting model on the host range.
- A control line's indent is set to the indent of the surrounding template-text block, plus one indent level per open control block. The `%` stays the first non-space character.
- Attribute lists inside a tag do not wrap.
- `NEWLINE_ESCAPE`, `MakoTextTag` bodies, comments, and raw doc bodies are `Block.isReadOnly` and never change bytes.
- Blank lines inside template text are kept.

No code-style panel in this phase. Indent size comes from the project's common indent options.

Test: a formatter fixture with a nested `% if` inside `% for`, a `<% %>` block, a trailing `\`, and a `<%text>` body. Assert the `\` line and the text body are byte-identical and the control lines gained indent.

### 5.6 Gutters

`MakoLineMarkerProvider`:

| Line | Icon target |
| --- | --- |
| `<%inherit>` | the parent file |
| a file that has indexed children | a "inherited by" list of those files |
| `<%include>` and `<%namespace file>` | the target file |
| a named block or top-level def that a parent also defines | the parent's definition ("rendered by parent" for a block) |
| a parent block that a child overrides | the overriding definitions |

Markers call `NavigationGutterIconBuilder`. A missing target draws nothing.

Test: the `base` / `child` fixture from Phase 4 asserts the inherit marker, the inherited-by marker, and the overridden-block marker.

### Phase 5 exit

The authoring table in the full plan works for tags, attributes, closers, control keywords, folding, structure, breadcrumbs, live templates, surround, formatting, and inheritance gutters. Typing tests cover each handler. `./gradlew check` passes, including Phases 1–4.

Spellcheck from Phase 2 and Python injection from Phase 3 still apply to the formatted file. The formatter test asserts an expression inside formatted HTML is still one `MakoExpression`.

## Work this plan does not include

Leave these for Phase 6 and Phase 7. They are specified in the full plan and have no tasks here.

- The Python Template Languages dropdown, and treating `.html` in a template folder as Mako.
- `TemplateContextProvider` and the Mako `render` / `get_template` / `get_def` provider.
- The preview action, output encoding, and the traceback console filter.
- Marketplace listing, screenshots, and `verifyPlugin` against a second IDE.

Also unchanged, because they are runtime:

- Cache backends, `preprocessor`, `module_directory`.
- Message extraction.
- Breakpoints mapped onto generated Python.

## Suggested order of pull requests

One pull request per slice, so a failure bisects cleanly. Phases 1 through 3 did not follow this split. The language core and Phase 2 landed together, and Phase 3 is its own branch.

| PR | Slice | State |
| --- | --- | --- |
| 1 | 1.1–1.2 language and lexer | Done inside `33dd387`, except the file-type icon |
| 2 | 1.3 parser, PSI, oracle | Flat parser and PSI are in `33dd387`. Nested PSI, stub types, and the oracle are open |
| 3 | 1.4–1.5 highlighting and structural inspections | Highlighter is in `33dd387`. Color page, braces, commenter, quote handler, and inspections are open |
| 4 | Phase 2 | Done, `33dd387`, merged as `3a1da27` |
| 5 | Phase 3, including the PyCharm test classpath | Done, `7f11572` |
| 6 | 4.1–4.3 settings, stubs, URIs | Not started |
| 7 | 4.4–4.6 references, rename, index inspections | Not started |
| 8 | 5.1–5.3 completion, typing, structure | Not started |
| 9 | 5.4–5.6 templates, formatter, gutters | Not started |

Phase 0, the shipped part of Phase 1, Phase 2, and Phase 3 are done. Do not restart them, and do not wait on the oracle before Phase 4. The open Phase 1 slices can land before or beside Phase 4. A nested parser change has to keep the Phase 3 injection tests green.
