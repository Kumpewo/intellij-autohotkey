<!-- Keep a Changelog guide -> https://keepachangelog.com -->

# AutoHotkey v2 Plugin Changelog

## [Unreleased]

## [1.1.0] - 2026-11-15

### Added

- Local variables and parameters of functions are resolved by the scoping rules of AutoHotkey v2 (also nested
  functions and closures, `static` nested functions, `global` declarations and assume-global functions): completion
  inside a function offers them first, *Ctrl+B* leads to their definition, *Find Usages* and the highlighting of
  occurrences only show the same variable, and *Rename* works directly in the editor. A parameter's name after
  `@param` in the doc comment is renamed with it, and *Ctrl+B* on it leads to the parameter.
- *Rename* where nothing can be renamed (global variable, built-in name, keyword ...) shows a hint instead of
  doing nothing.
- Own color *Core: Reserved value* for `true`, `false`, `unset`, `this` and `super` (bold purple by
  default).

### Fixed

- Numbers in scientific notation (`1e4`, `1.0e4`, `-2.1E-4`, `.5e3`) are recognized as one number.

## [1.0.0] - 2026-11-15

First public release.

### Added

- **Editor**
  - Parser for AutoHotkey v2 with error highlighting: expressions, commands without parentheses, hotkeys,
    hotstrings, remappings, classes and properties, `switch`, labels, continuation lines and sections.
  - Files that require another AutoHotkey version are left unparsed; without `#Requires` the plugin detects
    v1 code and offers to insert the matching `#Requires` line.
  - `;@ignore-next-line` hides the parser errors of the next line.
  - Highlighting by role: flow control, reserved words, directives, built-in variables, functions, classes,
    methods and properties, hotkeys, hotstrings, labels, escape sequences; colors in four groups on
    *Editor > Color Scheme > AutoHotkey*.
  - Structure view, breadcrumbs, sticky lines, brace matching (also `%...%`), quotes and `%` in pairs,
    comment/uncomment.
  - Code folding of blocks, comments, continuation sections, `#Include` lines and `;region` ... `;endregion`.
  - Highlighting of the occurrences of the name under the caret.
  - Spell checking of comments, strings and hotstrings, with a dictionary of the built-in names.
- **Completion and parameter info**
  - Completion of directives, keywords, built-in and own names, members after the dot, labels, key names in
    `Send` strings and hotkeys, parameter values, event names for `OnEvent`, options in option strings (`MsgBox`,
    `Gui`, `Gui.Add` ...), and file paths in `#Include`.
  - Parameter info for built-in functions and directives, methods of built-in classes and own functions and
    methods.
  - Type declarations `;@declare-type`, `;@declare-auto-type` and private members `;@declare-private`, also
    for a whole project through an included `pragmas.ahk`.
- **Navigation and help**
  - Go to declaration for functions, classes, members, fields, base classes, labels and `#Include` files,
    in the file and its directly included files; the global library folders appear as the library
    *AutoHotkey Lib*.
  - Find Usages for functions, methods, classes, properties, fields and labels (the file of the definition and the
    files that include it directly).
  - Go to Symbol and Go to Class over all AutoHotkey files of the project: functions, methods, classes,
    properties, fields, labels, hotkeys and hotstrings, also by qualified name (`Outer.Inner`).
  - Rename of functions, methods, classes, properties, fields and labels, always with preview (also in files that
    include the definition indirectly, optionally in comments and strings).
  - Quick documentation from the bundled AutoHotkey help (v2.0.31), external help (Shift+F1), help for the
    plugin's pragmas and the Ahk2Exe compiler directives, *Help > AutoHotkey Help* and a user manual
    (*Help > AutoHotkey Plugin Manual*).
  - Doc comments `/** ... */` for own functions, methods and classes (Markdown, `@param`, `@returns`, `@throws`,
    `@example`, `@see`, `@deprecated`): quick documentation, completion list, parameter info, parameter types,
    struck-out deprecated uses and a skeleton after `/**` + Enter.
  - Formatting of doc comments on reformat, configurable on the code style tab *Doc Comments* (alignment, blank
    lines, wrapping at the right margin, `@param` handling), off by default.
- **Formatting**
  - Code formatter with its own code style page and optional case adjustment of keywords and built-in names.
  - Live templates, *Surround With* and a file template for new scripts.
- **Running, validating, compiling, debugging**
  - Run configuration with clickable load errors in the console.
  - Validation with `AutoHotkey /validate` (manually, after reformatting or on save), errors marked in the code.
  - Compilation with Ahk2Exe; a run configuration can compile first and run the executable in a real console.
  - Debugger using the DBGp engine of AutoHotkey v2: line and exception breakpoints (with conditions and log
    expressions), stepping, call stack, variables with paging, set value, watches, hover, inline values,
    attach to and detach from running scripts.
