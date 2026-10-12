# Mako template support for the IntelliJ Platform

Plan for a full Mako 1.4.3 language plugin, built in this repository. The language reference is the [Mako 1.4.3 documentation](https://docs.makotemplates.org/en/latest/) and the 1.4.3 runtime (`mako/lexer.py`, `mako/parsetree.py`, `mako/runtime.py`).

The plugin makes `.mako` and `.mak` files, and any file the user maps to Mako, a first-class language: structure, highlighting, Python inside every code island, HTML (or another data language) in the text, navigation across templates, and diagnostics that match the compiler.

## Product

| Item | Choice |
| --- | --- |
| Plugin name | Mako |
| Plugin id | `com.mako.support` |
| Language version | Mako 1.4.3. Behavior that changed across 1.x is the 1.4.3 behavior. |
| Primary IDE | PyCharm 2025.3 (unified product). Template-language APIs are available there without a paid-only module. |
| Also runs in | IntelliJ IDEA 2025.3 and other IDEs that have the Python plugin installed. |
| Implementation | Kotlin, on the current Gradle scaffold (`intellij-platform` plugin, JDK 21). |
| Sample code | Removed in Phase 0: tool window, startup activity, project service, and their tests. |

`build.gradle.kts` targets `pycharm("2025.3.6.1")` and also bundles `PythonCore`, because the PyCharm coordinate alone did not put Python classes on the compile classpath. `plugin.xml` depends on `com.intellij.modules.platform`, `com.intellij.modules.xml`, the spellchecker module, and `com.intellij.modules.python`. `runIde` is PyCharm. A separate `runPyCharm` task remains. Slice-level status is in [phases-1-5.md](phases-1-5.md).

## What "full" means

A Mako file is three languages at once.

1. **Mako structure.** Tags, control lines, comments, text, and the nesting rules the compiler enforces.
2. **Python.** Every code island is real Python, with Mako's runtime names, defs, page arguments, imports, and loop variables in scope.
3. **The data language.** The text between Mako constructs is HTML by default, and can be XML, CSS, JavaScript, or plain text per file or per directory.

The plugin owns (1) and the seams of (2) and (3). It does not reimplement Python or HTML. It reuses the Python plugin and the platform template-data-language support.

Runtime configuration that lives only on `Template()` / `TemplateLookup()` in Python (cache backends, `module_directory`, `preprocessor`, `strict_undefined` at render time) is exposed as plugin settings where it changes editing. The plugin does not embed a cache implementation.

## Prior art

JetBrains removed bundled Mako support in PyCharm 2021.3 ([PY-51736](https://youtrack.jetbrains.com/issue/PY-51736)). A community plugin restores highlighting, HTML splitting, raw Python injection, tag-name completion, and `file=` navigation. The author lists the remaining gaps: attribute-value completion, closing-tag completion, control-terminator completion and checking, and `<%def>` / `<%namespace>` as a real Python namespace.

This plan includes those gaps and the rest of the language: inheritance symbols, filters, page arguments, loop context, module namespaces, rename, render context, and traceback navigation.

## Language coverage

Every row is a required behavior. "Python" means a real injected Python fragment, with completion, resolve, types, and inspections from the Python plugin.

### Text, escapes, and line rules

| Construct | Editor behavior |
| --- | --- |
| Plain text | Parsed as the template data language. |
| `$$` producing a literal `$` | Confirm against `mako/lexer.py` 1.4.3 during Phase 1 and match it. A literal dollar is text, with no expression highlighting. |
| `%%` at the first non-space of a line | One `%` of literal text. `%%%` is a literal `%%` plus text. A `%` later on the line is text. |
| `\` at end of line | Newline filter. The escaped newline stays in the file and is highlighted as an operator. The formatter must not delete it. |
| Magic comment `## -*- coding: utf-8 -*-` | Recognized as the input encoding (PEP 263). Default when absent is UTF-8, matching Mako 1.1.3+. |
| `${ ... }` | Expression. Nested braces, strings, and Python comments inside the expression do not end it. Filters after `\|` are a separate list. |
| `%` control line | The `%` is a control marker when it is the first non-space character. The rest of the line is a Python statement. Indentation is not significant to Mako. |

### Comments

| Construct | Editor behavior |
| --- | --- |
| `##` as the first non-space on a line | Line comment through end of line. Comment action (`Ctrl+/`) inserts this. |
| `<%doc> ... </%doc>` | Block comment. Block-comment action wraps with this. Contents are not lexed as Mako or Python. |

A `#` inside a control line or a Python block is a Python comment, because that text is Python. The line-comment rule applies only when `##` itself starts the line.

### Control structures

The marker accepts the full set of Python colon statements Mako documents: `if` / `elif` / `else`, `for`, `while`, `with`, `try` / `except` / `finally`, and `def`. Any other Python statement (`% x = 1`, `% import os`) is a one-line statement and does not open a block.

Closing and interior keywords:

| Opens | Interior | Closes |
| --- | --- | --- |
| `% if` | `% elif`, `% else` | `% endif` |
| `% for` | | `% endfor` |
| `% while` | | `% endwhile` |
| `% with` | | `% endwith` |
| `% try` | `% except`, `% else`, `% finally` | `% endtry` |
| `% def` | | `% enddef` |

`% def` is legal and gets a weak inspection offering `<%def>`, which is the supported way to declare a template function.

Required editing:

- Brace matching from an opener to its `end*` line, and from `elif` / `else` / `except` / `finally` back to the opener.
- Completion of the closing line.
- An unmatched opener, an interior keyword with no opener, and a closer that names the wrong keyword are errors.
- Typing the `:` of an opener offers to insert the closer.

### Python blocks

| Construct | Scope in the generated module | Injection |
| --- | --- | --- |
| `<% ... %>` | Inside the render function. Sees context variables, defs, and assignments from earlier blocks. | Python statement fragment. |
| `<%! ... %>` | Module level. Runs at import, before render. No `context`, no `self`, no page arguments. | Python module fragment. |
| `<% return STOP_RENDERING %>` | Early exit. `STOP_RENDERING` is the empty string. | `STOP_RENDERING` is a builtin in render scope. Returning any other value is legal Python and is how older templates exited (`return ''`). |

Multiple `<%! %>` blocks are one module-level region in source order. The injector presents them as one module preamble so a name defined in an earlier block resolves in a later one.

### Tags

Tag syntax is `<%keyword attr="value" ...>` or `<%keyword ...> body </%keyword>`. A comma between attributes is accepted for compatibility and highlighted as a weak warning, because it is not the real syntax (`<%page cached="True", args="a, b"/>` still parses in 1.4).

Attribute values are literal strings. A value that contains `${...}` has that part evaluated as a Python expression. Completion and resolve work inside those embedded expressions.

| Tag | Required attributes | Optional attributes | Body |
| --- | --- | --- | --- |
| `<%page>` | | `args`, `expression_filter`, `enable_loop`, `cached`, `cache_key`, `cache_timeout`, `cache_type`, `cache_dir`, `cache_url`, `cache_region`, any other `cache_*` | none |
| `<%include>` | `file` | `args` | none |
| `<%inherit>` | `file` | | none |
| `<%namespace>` | `file` or `module` or a body | `name` (required unless `import` is set), `import`, `inheritable` | optional `<%def>` children |
| `<%def>` | `name` (a Python signature) | `filter`, `decorator`, `buffered`, `cached`, any `cache_*` | template body |
| `<%block>` | | `name`, `filter`, `decorator`, `buffered`, `cached`, any `cache_*`, `args` (named blocks only) | template body |
| `<%call>` | `expr` | `args` | template body |
| `<%doc>` | | | unparsed text |
| `<%text>` | | `filter` | unparsed text, including fake Mako syntax |
| `<%ns:def>` | | any attribute, plus `args` | template body |

`<%text>` and `<%doc>` bodies are opaque. `${}`, `<%`, and `%` inside them are text.

Completion inserts a skeleton: `/>` for empty tags, a closing tag for tags that take a body, and the usual required attribute with the caret inside the quotes.

#### Page tag

`args` is a Python parameter list, the same shape as a function signature without the name. It supports defaults and `**kwargs`. When `**kwargs` is absent, Mako adds `**pageargs`, and `pageargs` is a dict in scope.

Only the first `<%page>` is used. A later one is a warning.

`expression_filter` is a filter list applied to every expression in the file. `enable_loop` is a boolean expression, normally `"True"` or `"False"`.

#### Include, inherit, namespace files

`file` resolution follows `TemplateLookup.adjust_uri`:

- A URI that starts with `/` is relative to a configured template root.
- Any other URI is relative to the directory of the current template, then searched in the template roots.
- `file="${expr}"` is dynamic. Complete and resolve inside the expression. Do not report an unresolved-file error when the path is not a literal.
- `<%include args="...">` passes keyword arguments. The argument text is Python keyword arguments, and the names are checked against the included template's `<%page args>` when that file resolves.

`<%namespace module="pkg.mod">` resolves as a Python import against the project SDK. Callables in that module are namespace members. Their first parameter is `context` and is hidden from completion at the call site, because `Namespace` binds it. Functions decorated with `mako.runtime.supports_caller` are valid custom tags.

`import="foo, bar"` and `import="*"` pull top-level defs into the local scope. `import="*"` gets an information-level hint: Mako documents that this form is slower. Imported names override context variables of the same name.

`inheritable="True"` attaches the namespace to `self`, so an inheriting template completes `self.<name>.<def>()`.

A `<%namespace>` with a body exports the defs declared inside it and overrides defs of the same name loaded from `file` or `module`.

#### Defs and blocks

`<%def name="account(accountname, type='regular')">` declares a function. Top-level defs are visible in the whole template, including above the definition, and are exported to other templates. A def nested in another def is visible only inside the enclosing def, like a Python closure. Assignment inside a def makes that name local to the def for the whole def, matching Python scoping. The injector must use the same rule, or `${x}` before `<% x = 1 %>` inside a def will resolve to the outer `x` and hide a real `UnboundLocalError`.

Named `<%block>` rules, enforced as errors:

- The name is unique in the file, at any nesting depth.
- A named block cannot be nested inside a `<%def>`, `<%call>`, or `<%ns:def>`.
- A named block cannot share its name with a top-level def.
- A named block has no parameter list in `name`. The separate `args` attribute is allowed and lists names from `<%page args>` that the block needs.
- Anonymous blocks may nest anywhere, including inside defs, calls, and loops. They see the surrounding Python scope.

Both defs and named blocks are callable. A named block also renders itself at its source location in the base-most template.

#### Calls with content

`<%self:buildtable arg="value" args="col">` and `<%call expr="buildtable()" args="col">` are the same feature. Inside the called def, `caller` is a namespace whose `body()` renders the tag body, and whose other members are the defs declared in that body. `args` on the call declares the parameters `body()` receives (`caller.body(col=col)`).

Custom-tag attribute values are strings unless wrapped in `${}`. Completion inside `${}` is an expression. The tag name `ns:def` resolves to a def on that namespace.

### Filters

Built-in filter names, completed and checked wherever a filter list appears (`${expr \| ...}`, `expression_filter`, `filter` on def, block, and text):

| Name | Meaning |
| --- | --- |
| `h` | HTML escape (`markupsafe.escape`) |
| `u` | URL escape |
| `x` | XML escape |
| `trim` | `str.strip` |
| `entity` | HTML entity encoding |
| `str` | default string conversion |
| `unicode` | alias of `str` since Mako 1.2 |
| `decode.<encoding>` | decode bytes with that encoding, for example `decode.utf8` |
| `n` | drop default filters and `<%page expression_filter>` for this expression |

Any other name is a Python expression resolved in the local namespace: a function defined in `<%! %>`, or an imported callable such as `myfilters.tagfilter`. Unknown bare names are an unresolved-reference warning.

Plugin setting `default filters` mirrors `TemplateLookup(default_filters=...)`. The default value is `str`. Setting `imports` mirrors `TemplateLookup(imports=...)` and is prepended to every template's module preamble so those names resolve.

`filter` on a def or block implies buffering. `buffered="True"` is the explicit form. `decorator` is a Python expression that evaluates to a callable.

### Runtime names

These names are in scope in render code (`${}`, `<% %>`, control lines, def and block bodies). They are absent from `<%! %>`.

| Name | Where it comes from |
| --- | --- |
| `context` | `mako.runtime.Context`. Reserved. Members: `lookup`, `kwargs`, `keys`, `get`, `write`, `writer`, `namespaces`, indexing. |
| `UNDEFINED` | `mako.runtime.UNDEFINED`. Reserved. Falsy; `str()` raises `NameError`. |
| `STOP_RENDERING` | Empty string used as the return value of an early exit. |
| `local` | Namespace of the current template. Top-level defs, `body()`, `uri`, `filename`, `module`, `attr`, `cache`, `get_namespace`, `get_template`, `include_file`, `get_cached`. |
| `self` | Top of the inheritance chain. Same as `local` when the file does not inherit. Inheritable namespaces and the top-most def or block of each name live here. |
| `parent` | Immediate parent template. Present when this file inherits. |
| `next` | Immediate child in the inheritance chain. Present in an inherited template. |
| `caller` | Namespace of the `<%call>` / custom tag that invoked this def. Members are `body` and defs declared in the call body. |
| `capture` | `capture(fn, *args, **kwargs)` returns the def's output as a string. The first argument is the callable, not the call. |
| `pageargs` | Dict of extra keyword arguments when the page signature has no `**kwargs`. |
| `loop` | Inside `% for` when loop context is enabled. Reserved, so a context variable named `loop` is a conflict. |

`loop` members, from `mako.runtime.LoopContext` in 1.4.3:

| Member | Type | Notes |
| --- | --- | --- |
| `index` | int | 0-based |
| `reverse_index` | int | requires `__len__` |
| `first` | bool | |
| `last` | bool | requires `__len__` |
| `even` | bool | |
| `odd` | bool | |
| `parent` | `LoopContext` | outer `% for` |
| `cycle(*values)` | method | |

Older tutorials mention `index0`, `revindex`, and `revindex0`. Those names are not in 1.4.3. Completion offers the 1.4.3 names only.

`loop` is in scope only between a `% for` and its `% endfor`, including nested blocks, and only when `enable_loop` is on. The plugin default is on, matching Mako. `<%page enable_loop="False"/>` turns it off for that file, and a variable named `loop` is then an ordinary name.

`self.attr.<name>` resolves to a module-level assignment in `<%! %>` of this template or an inherited template. An inheriting assignment overrides the parent.

### Inheritance navigation

`<%inherit file="..."/>` links the file to its parent.

- `self.<def>` and `self.<block>` resolve to the top-most definition of that name in the chain that ends at the current file.
- `parent.<def>` resolves to the definition on the immediate parent.
- `next.body()` and `next.<block>` resolve to the child side. From a parent file opened alone, `next` has an unknown child; completion still offers `body` and the parent's own overridable names, and a gutter marks the file as an inherited base once children are indexed.
- A named block that exists in a parent is rendered by the parent, not at the child's definition. A gutter on the child block navigates to the parent location that renders it.
- `<%include>` does not participate in inheritance. An inspection explains that a block defined only in an included file is not overridable, and offers the documented fix of `<%namespace>` plus an explicit call when the include target has a single obvious block of the same name.

### Caching syntax

Caching is a set of attributes, not a runtime the plugin implements. On `<%page>`, `<%def>`, and `<%block>`:

- `cached` (boolean expression)
- `cache_key` (string, may contain `${}`)
- `cache_timeout` (integer expression)
- `cache_type` (`memory`, `file`, `dbm`, `ext:memcached`, `memcached`)
- `cache_dir`, `cache_url`, `cache_region`
- any other `cache_*` attribute, accepted and passed through, because backends take arbitrary `cache_*` names

`local.cache` and `self.cache` complete `get`, `set`, `invalidate`, `invalidate_body`, `invalidate_def`, `get_or_create`, and `impl`.

### Unicode

- Honor a PEP 263 cookie. Otherwise decode the file as UTF-8.
- Non-text expression results are passed through `str` by default. With `default_filters` set to `decode.<encoding>`, highlight a string literal that is still a byte string (`b"..."`) passed to an expression as a warning.
- `output_encoding` is a project setting used by the preview action. It does not change highlighting.

## Architecture

```
src/main/kotlin/com/mako/support/
  lang/            MakoLanguage, file view provider, brace matcher, commenter, quote handler
  lexer/           hand-written lexer, state per Mako lexer mode
  parser/          PSI builder for the node tree below
  psi/             element types, PSI classes, PSI utilities
  highlight/       highlighter, color settings, annotator for semantic colors
  inject/          Python injection hosts and the synthetic preamble
  template/        template-data element type, data-language mapping
  index/           Stub index of defs, blocks, namespaces, inherits, includes, page args
  resolve/         reference contributors for files, defs, filters, namespaces
  completion/      tags, attributes, filters, control closers, files, symbols
  inspections/     structure errors and Mako-specific warnings
  structure/       structure view, breadcrumbs, folding
  editor/          enter handler, typed handler, live templates, surrounders
  settings/        project settings and Python template-language registration
  run/             preview action, console filter for tracebacks
src/test/
  testData/        corpus of .mako files with expected PSI, highlights, completions, resolve
  oracle/          Python script that dumps mako.lexer output for the corpus
```

After Phase 3 the packages on disk are `lang/`, `lexer/`, `parser/`, `psi/`, `highlight/` (highlighter only), `template/`, `inject/`, and `spellcheck/`. Stub resources are `src/main/resources/mako-stubs/*.py`. The other directories in this tree are still future work. The parser that shipped is flat: control lines and tag bodies are siblings, not nested the way the sketch below shows.

### Lexer and parser

Write a hand-written lexer. Mako's grammar is mode-heavy (text, expression, expression-filter, tag, tag-attribute, raw text, comment, control line) and a JFlex port of `mako/lexer.py` fights the brace-matching rules inside expressions. The lexer is incremental and does not call Python.

Parser output is a shallow PSI tree. Python and HTML are not parsed by this parser.

```
MakoFile
  MakoPageTag
  MakoInheritTag
  MakoNamespaceTag
  MakoIncludeTag
  MakoDef | MakoBlock | MakoCall | MakoCustomTag
    MakoControlBlock (if / for / while / with / try)
      MakoControlInterior (elif / else / except / finally)
    MakoExpression
      MakoFilterList
    MakoPythonBlock          <% %>
    MakoModuleBlock          <%! %>
    MakoComment
    MakoTemplateText         re-parsed by the data language
    MakoTextTag / MakoDocTag
```

Each code island is an injection host. Each `MakoTemplateText` run is a template-data element, re-lexed by HTML, XML, or whatever the file's data language is.

### Conformance oracle

A dev-only Python script loads Mako 1.4.3, runs `mako.lexer.Lexer(source).parse()`, and writes a JSON summary of node kinds, spans, control keywords, and tag attributes. Lexer and parser tests compare the plugin's PSI against that JSON for the corpus. The corpus covers every row in the language-coverage tables, plus the edge cases Mako's own tests describe publicly: percent escaping, quoted attributes that contain `>` and `${}`, a comma between attributes, unclosed tags, and nested control lines.

The script is not shipped in the plugin. Pin the Mako version in the script's requirements so an upstream lexer change is a deliberate bump.

### Python injection

Phase 3 shipped the host table, the four stub classes pasted into the prefix, module-block concatenation, page args, local defs, and `loop` inside `% for`. Project `imports`, inherited defs, `caller.body`, and the `strict_undefined` warning are still Phase 4. The `from mako_support_stubs import ...` line was dropped; the stub text is inlined, and the resource files are `.py`. The execution notes are in [phases-1-5.md](phases-1-5.md).

One `MultiHostInjector` serves every host. The fragment kind differs:

| Host | Fragment wrapped as |
| --- | --- |
| `${expr}` | expression: `_ = (expr)` |
| `${expr \| filters}` | expression for `expr`; filters parsed by the Mako parser, then each custom filter injected as an expression |
| `<% %>` | statements |
| `<%! %>` | module body, concatenated in source order |
| `%` line | one statement |
| `<%def name="...">` and `<%page args="...">` | `def _sig(name-or-args): pass` so the Python parser checks the signature |
| `<%call expr="...">` | expression |
| attribute `${...}` | expression |
| `decorator="..."` | expression |

The prefix injected ahead of render-scope fragments is generated per file, not a fixed string. It contains:

- imports from the project `imports` setting
- assignments and imports collected from `<%! %>`
- `<%page args>` as parameters of the render function
- top-level defs as function stubs whose signatures come from `name`
- imported names from `<%namespace import>`
- defs from inherited templates and from `self` / `parent` / `next`, as attributes of stub namespace objects
- `loop` typed as `LoopContext` inside `for` bodies
- `caller` typed with `body` and the defs declared in the current call

Stub classes for `Context`, `Namespace`, `LoopContext`, and `Undefined` live in a small Python stub package shipped as a plugin resource (`mako-stubs`). Injection adds that package to the fragment's extra paths. The stubs document the 1.4.3 members listed above and nothing else.

`strict_undefined` is a project setting, default off.

- Off: a name that is not defined in the template, not imported, and not found in a render-context provider is a weak warning ("this name is `UNDEFINED` unless a caller passes it").
- On: the same name is an unresolved-reference error.

Names provided by a `TemplateContextProvider` (below) are real references in both modes.

### Template data language

`MakoLanguage` implements the platform `TemplateLanguage`. `MakoFileViewProvider` is a `TemplateLanguageFileViewProvider`. Text runs use `TemplateDataElementType`, so HTML completion, Emmet, tag closing, and HTML inspections work between Mako tags.

The data language comes from Settings → Languages & Frameworks → Template Data Languages, with HTML as the default. A file can be plain text, which is the right choice for email bodies, SQL, and generated source code.

### Index and resolve

A stub index stores, per Mako file:

- top-level def names and signatures
- named block names
- `<%page args>` names
- `<%namespace>` bindings (`name`, `file` or `module`, `import` list, `inheritable`)
- `<%inherit>` and `<%include>` URIs
- module-level names assigned in `<%! %>` (for `self.attr`)

References:

| From | To |
| --- | --- |
| `file="..."` | template file via `adjust_uri` |
| `module="..."` | Python module |
| bare call `name(...)` in an expression | local def, imported def, or a context variable |
| `ns.name(...)` | def exported by that namespace's template, or a public function in its module |
| `self.name`, `parent.name`, `next.name`, `local.name` | inheritance-aware def or block |
| `caller.name` | def declared in the body of the call that targets this def |
| `self.attr.name` | `<%! %>` assignment on this file or an ancestor |
| filter name | builtin, or a Python callable in scope |

Find Usages and Rename use these references. Renaming a def renames `name="..."` and every call, including `<%ns:def>` tag names. Renaming a file updates literal `file` attributes whose resolved target is that file. Relative and root-absolute URIs are rewritten with the same `adjust_uri` rules.

### Render context

Register a Python template-language provider so Mako appears under Settings → Languages & Frameworks → Python Template Languages, next to Django, Jinja2, and Chameleon. Files in configured template folders with a selected template file type are treated as Mako even when their extension is `.html`.

Reuse the platform `TemplateContextProvider` results. A Flask or Django view that passes a dict into `render` contributes those names, with their inferred types, to the matching template. That reuses PyCharm's existing extraction.

Add one Mako-specific provider for the calls Mako projects actually make:

- `template.render(name=value, ...)` and `template.render(**ctx)` when `template` is a `Template` whose file is literal
- `lookup.get_template("uri").render(...)` with a literal URI
- `template.get_def("name").render(...)` contributes arguments to that def

A non-literal template name contributes nothing and raises no error.

### Tracebacks and preview

Mako's `RichTraceback` records `(filename, lineno, function, line)` for template lines, and `CompileException` reports `at line: N char: M`. A console filter maps both forms onto the source file, so a click in the Run tool window opens the template.

A **Mako: Preview Template** action renders the current file with the project interpreter. The action requires `mako` installed in the selected SDK. It loads the file through a `TemplateLookup` pointed at the configured template roots, passes a JSON object the user edits in a small dialog (or `{}`), and prints the output in a tool window. Failures go through the same console filter. This action is the only place the plugin executes a template.

## Settings

Project settings, under Languages & Frameworks → Mako:

| Setting | Default | Effect |
| --- | --- | --- |
| Template directories | PyCharm template folders, else a `templates` directory if one exists | URI resolution |
| Extra extensions | `.mako`, `.mak` are built into the file type | additional patterns |
| Default filters | `str` | injection and the `n` filter's meaning |
| Imports | empty | prepended to the module preamble |
| Enable loop | on | whether `loop` is reserved and in scope in `% for` |
| Strict undefined | off | warning vs error for unknown names |
| Output encoding | empty (Unicode string) | preview action only |

Color scheme page **Mako**, with separate keys for control markers, tag names, attribute names, expressions, filters, comments, module blocks, and the newline-escape backslash. Template text uses the data language's colors.

## Editing features

| Feature | Behavior |
| --- | --- |
| Highlighting | Mako tokens plus semantic colors for resolved vs unresolved names. |
| Brace matching | `${}`, `<% %>`, `<%! %>`, paired tags, control open/close. |
| Quote handler | Attribute quotes. Does not treat `${` as a string. |
| Commenter | `##` and `<%doc>`. |
| Enter | Inside a control block, indent one level. Inside `<% %>`, delegate to the Python enter handler. |
| Typed `%` after whitespace at column of a line | Popup of control keywords. |
| Typed `<%` | Popup of tag names with skeletons. |
| Typed attribute space | Popup of attributes still unused on that tag. |
| Closing tags | Auto-insert `</%def>` and the other paired closers. Match highlighting across pairs. |
| Control closers | Complete `% endif` / `% endfor` / `% endwhile` / `% endwith` / `% endtry` / `% enddef`. |
| Folding | Defs, blocks, namespaces, Python blocks, doc blocks, control blocks. |
| Structure view | Inherit, includes, namespaces, page args, defs, named blocks. |
| Breadcrumbs | Tag and control-block stack. |
| Live templates | `def`, `block`, `inherit`, `include`, `namespace`, `for`, `if`, `call`. |
| Surround With | `% if`, `% for`, `<%def>`, `<%block>`, `<%text>`. |
| Formatter | Format injected Python with the Python formatter. Indent Mako control lines to the surrounding data-language indent. Preserve `\` newline filters, `<%text>` bodies, and comments byte for byte. |
| Spellcheck | On template text and comments. Off inside Python, tag names, and expressions. |
| Gutter | Inherited-by / inherits, include targets, and "rendered by parent" on overridden blocks. |

## Inspections

Errors, matching conditions the compiler rejects or that make the file unambiguous wrong:

- Unclosed tag, unclosed `${`, unclosed `<%`.
- Mismatched control closer, or an interior keyword with no opener.
- `<%def>` missing `name`, `<%include>` or `<%inherit>` missing `file`, `<%call>` missing `expr`, `<%namespace>` missing a source (`file`, `module`, or body) and missing `name` when `import` is absent.
- Duplicate named block. Named block inside a def or call. Block name colliding with a top-level def.
- More than one `<%page>` (warning: only the first is used).
- `args` on an anonymous block.
- Def signature or page-args list that is not valid Python.
- `file` or `module` literal that does not resolve.
- `import="name"` that is not a top-level def of the target.

Warnings:

- Unknown filter name.
- Bare `% def`, offering conversion to `<%def>`.
- `import="*"`.
- Comma between tag attributes.
- Unresolved name, severity depending on `strict_undefined`.
- Assignment to `context`, `UNDEFINED`, or `loop` (when loop is enabled). These are reserved.
- `<%include>` of a file whose blocks the author then tries to override. The inspection text explains that include does not inherit, and points at `<%namespace>`.
- `enable_loop` false while the file uses `loop.*`.

Quick fixes ship with the inspections that have one safe edit: insert the closer, insert the missing attribute, convert `% def` to `<%def>`, drop the extra comma, add a template directory that contains the unresolved file.

## Phases

Each phase is shippable. Later phases depend on earlier ones. Tests from a phase stay green. Status on 2026-10-12:

| Phase | State |
| --- | --- |
| 0 | Done (`647d3a1`). Python `<depends>` and the PyCharm Gradle target landed in Phase 3. |
| 1 | Partial. Language, file type without an icon, lexer, flat PSI, and the highlighter shipped in `33dd387`. Icon, color settings, brace matcher, commenter, quote handler, nested PSI, stub types, oracle, and structural inspections are open. |
| 2 | Done (`33dd387`, merged to `main` as `3a1da27`). |
| 3 | Done (`7f11572` on `feature/phase-3-python-injection`). 26 tests, 0 failures. |
| 4–7 | Not started. |

### Phase 0 — Turn the template into this plugin — done

- Set plugin id, name, vendor, and description. Done in `647d3a1`.
- Delete the sample tool window, project activity, service, and their tests. Done.
- Depend on `com.intellij.modules.python` and the XML module. XML was already present. The Python module dependency was added in Phase 3 (`7f11572`), together with `pycharm("2025.3.6.1")` and `bundledPlugin("PythonCore")`.
- Add a PyCharm run configuration next to the existing IDEA one. Done. `runIde` itself is now the PyCharm target, and `runPyCharm` remains.
- Keep the Gradle changelog and GitHub workflows. Done. `template-cleanup.yml` and `template-verify.yml` stay deleted.

Exit: met for the empty plugin. `./gradlew check` is green, now with the Phase 2 and Phase 3 tests included (26 tests).

### Phase 1 — Lexer, parser, highlighting — partial

- File type for `.mako` and `.mak`. Done. Its own icon is not done.
- Lexer, flat parser, PSI, and highlighter. Done in `33dd387`. Color settings, brace matcher, and commenter are not done. The quote handler is not done either.
- Oracle corpus and tests for every construct in the language-coverage tables. Not done.
- Structural inspections: unclosed constructs, bad control nesting, missing required attributes, duplicate blocks, extra `<%page>`. Not done.

The shipped parser is flat. Control lines and tag bodies are not nested, and there are no stub element types. Phase 4 has to introduce those types, or consume the flat attributes, until the rest of this phase nests the tree. `MakoInjectionModel` already walks the flat tree. A nested parser has to keep the injection tests green.

Exit: not met. Highlighting covers nested expressions, `<%text>`, `%%`, and filters. Broken nesting is not an inspection. HTML is a data language now (Phase 2), not a single plain-text token.

### Phase 2 — Data language — done

- Template file view provider and template-data element type. Done.
- HTML by default. A per-file plain-text data language is tested. Per-directory mapping is the platform template-data setting.
- Spellcheck boundaries. Done. Comments and template text are checked. Expression bodies, Python blocks, tag keywords, attribute names, and control bodies are skipped.
- Tests that an HTML tag split by a Mako expression still completes and still matches its closer. Done (`MakoDataLanguageTest`).

Exit: met. `<div class="${cls}">` behaves as HTML around the expression and as Mako inside it.

### Phase 3 — Python injection — done

- Injector for every host kind, with the correct fragment wrapper. Done (`MakoPythonInjector`).
- Shipped `mako-stubs` for `Context`, `Namespace`, `LoopContext`, `Undefined`. Done, as `.py` resources whose text is pasted into the prefix. No `mako_support_stubs` import.
- Preamble: `<%! %>`, page args, local defs, builtin names, `loop` only inside `% for`. Done. `enable_loop` comes from the first `<%page>` until Phase 4 settings exist.
- Def-local scoping matches Python. Done, via an assignment scan in `MakoPythonText` and an open `def` in the prefix.
- Tests: completion of `context.write`, `loop.index`, a local def, and a name from `<%! %>`; a resolution failure for `context` inside `<%! %>`; signature errors on a bad `<%def name>`. Done, in `MakoPythonInjectionTest` (11 tests).

`MakoPythonTestLayout` keeps the PyCharm test sandbox from throwing in the pro helpers locator. It lives in the test source set and runs from `MakoPlatformTestCase.setUp`, not from the plugin.

Exit: met for completion and the Python highlighter on the tested hosts. `context` is absent from module blocks. `loop` exists only inside `% for`. Python inspections beyond syntax highlighting are the platform's, on the injected fragment. Cross-file inspections are Phase 4. There is no Phase 1 oracle suite.

### Phase 4 — Symbols across files

- Settings page: template directories, default filters, imports, enable loop, strict undefined.
- Stub index. File resolution with `adjust_uri`. Module resolution.
- Namespace imports, inheritable namespaces, `self` / `parent` / `next` / `caller` / `self.attr`.
- Filter completion, including `decode.<encoding>` and callables from `<%! %>`.
- Find Usages and Rename for defs, blocks, and files.
- Inspections that need the index: unresolved `file`, bad `import`, include-vs-inheritance, reserved names.

Exit: `<%inherit>`, `<%include>`, and `<%namespace>` navigate and complete across a fixture project. Rename of a def updates a custom tag in another file.

### Phase 5 — Authoring

- Tag and attribute completion with skeletons, closing-tag insertion, control-closer completion.
- Enter handler, folding, structure view, breadcrumbs, live templates, Surround With.
- Formatter policy from the editing-features table.
- Gutter navigation for inheritance.

Exit: the authoring gaps called out in the prior-art section are covered, with typing tests for each.

### Phase 6 — Framework integration and preview

- Python Template Languages registration, so `.html` in a template folder can be Mako.
- `TemplateContextProvider` bridge plus the Mako `render` / `get_template` / `get_def` provider.
- Console filter for `RichTraceback` and `CompileException`.
- Preview action.

Exit: a Flask-style `render` of a literal template name exposes its keyword arguments inside the template. A raised template error opened from the console lands on the Mako line.

### Phase 7 — Release

- Plugin description, change notes, and screenshots taken from the sandbox IDE.
- `verifyPlugin` against the current PyCharm 2025.3 and IDEA 2025.3 with the Python plugin.
- Marketplace listing. License stays MIT, matching this repository.

## Testing

| Layer | What it locks |
| --- | --- |
| Oracle tests | PSI spans against Mako 1.4.3 for the corpus. |
| Highlight tests | `checkHighlighting` on one file per construct. |
| Completion tests | tags, attributes, filters, closers, defs, `loop` members, files, modules. |
| Resolve tests | include, inherit, namespace import, `self` / `parent`, `caller.body`, `self.attr`. |
| Rename and Find Usages tests | def, block, file. |
| Injection tests | Python resolves a def parameter and a `<%! %>` import; `context` is absent in module blocks. |
| Inspection tests | every error and warning in the inspections section, including quick fixes. |
| View-provider tests | HTML completion across a Mako expression. |

Run `./gradlew check` on every phase. Add the oracle script to CI only if a CPython install is already available; otherwise run it locally and commit the JSON so CI stays Java-only. When Mako is bumped, regenerate the JSON and review the diff.

## Risks

| Risk | Response |
| --- | --- |
| Python-plugin APIs for template languages are not a public SDK contract. | Keep the provider behind one class. Phase 6 starts by reading the 2025.3 Python plugin's extension points in the sandbox, then coding against what is there. Highlighting and injection in Phases 1–5 use public platform APIs and ship even if the dropdown integration needs a narrower version range. |
| Injection prefixes that import the whole preamble can be slow on huge files. | Build the prefix from the stub index, cache it per file with a PSI dependency, and cap `import="*"` expansion to the indexed exports of the target. |
| Dynamic `file="${...}"` and computed inheritance cannot be resolved. | Resolve literals only. Dynamic values stay valid and unnavigable. |
| `preprocessor` can rewrite a template before lexing. | Out of scope. Document it. The plugin lexes the file the user sees. |
| Control-line `def` / `try` nesting has a few combinations the docs show only by example. | The oracle, not the docs paraphrase, decides. Where Mako 1.4.3 accepts a construct, the plugin accepts it. |

## Work deliberately left out

These are real Mako features and they stay out of the plugin because they are application runtime, not editing:

- Beaker, dogpile.cache, and custom `mako.cache` plugins.
- `module_directory`, `filesystem_checks`, and bytecode caching.
- `preprocessor` and the lexer `preprocessor` hook.
- Babel / `mako.ext.extract` message extraction. Revisit only if a user asks; the lexer will already be able to feed it.
- Debugging a generated Python module as if breakpoints were set on template lines. The console filter is the supported way to land on a failing line.

## First implementation step

Phase 0, Phase 2, and Phase 3 are done. The lexer, the flat parser, the highlighter, the HTML data layer, and Python injection are in the tree. Do not start over at Phase 0, and do not rebuild those pieces.

Still open inside Phase 1: the file-type icon, color settings, brace matcher, commenter, quote handler, structural inspections, nested control and tag PSI, stub element types, and the Mako 1.4.3 oracle. The next product phase is Phase 4 (settings, stub index, URI resolution, rename, cross-file inspections). Phase 4 can start before the oracle. A change that nests the parser has to keep the injection tests green. Phases 6 and 7 stay out until 4 and 5 are done.
