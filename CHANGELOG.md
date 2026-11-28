<!-- Keep a Changelog guide -> https://keepachangelog.com -->

# AutoHotkey v2 Plugin Changelog

## [Unreleased]

## [2.4.0] - 2026-11-28

### Added

- Completion offers global variables declared with `global` (at the top level or in a function, also in included
  files), also after `global` in a function; names that are only assigned are still not offered.
- `;@declare-type Class.member as Type` declares the class of a field or property; completion, parameter info and
  help follow chains such as `win.log.view.` and `this.log.`. A declaration in the file of a class applies wherever
  the class is used.
- `;@declare-member-type Type` declares the class of the field or property in the next line, right at its definition;
  live template `dm`.
- Class names in doc comments (`@param {Type}`, `@returns {Type}`) and in the type pragmas, and the member in
  `;@declare-type Class.member`, are references: *Ctrl+B*, *Ctrl+Q*, *Find Usages* and *Rename* reach them without
  the text search in comments.
- *Ctrl+Q* and hover on own properties and fields: kind, class, declared type and doc comment; *Ctrl+hover* shows
  the declared type. `/**` + *Enter* before a property with parameters adds the `@param` lines.

### Changed

- *Ctrl+Q* after the dot of a name with a declared type (or `this`) shows only the methods and properties of that
  class and its base classes, not those of other classes with the same name.
- A doc comment may be separated from its definition by pragma lines (`;@declare-member-type ...`).
- `this.` in an instance method, property or field initializer of a class offers only the members of that class and
  its base classes.
- Completion after the dot for a declared built-in type (`;@declare-type win as Gui`) no longer appends the members of
  all own classes; it offers only the members of the type and its base classes, as for own types.
- The settings file of plugin version 2.1 (`settings.ahk-plugin.ini`) is still read by all versions 2.x; version
  3.0.0 will no longer read it.

## [2.3.0] - 2026-11-26

### Added

- `settings.ahk-v2-plugin.ini`: completion (*Ctrl+Space* and while typing) of files of the project, folders, variables
  (`%A_MyDocuments%` …) and values (`yes`, `no`, `@standard`, `find:` …).
- User settings: a file with your own defaults, found in the project folder or above (*Languages & Frameworks >
  AutoHotkey*, *User settings file*), is inserted into new settings files, and by *Apply User Settings* into existing
  ones.
- The *Ctrl+Q* explanations of `settings.ahk-v2-plugin.ini` contain examples to copy.
- `Message` knows `{project-name}`.

### Changed

- Go to declaration, completion, parameter info, quick documentation, types and *Find Usages* now also cover files that
  are included indirectly, so one `#Include` of a file that includes a whole framework is enough. Files of language
  level v2.1 still only see the files they include directly.
- Library projects: a library file in a version folder (`Lib\v2\Encode.ahk`) becomes the source `Encode-v2.ahk` with
  the target `v2\Encode.ahk` in *Create Library Project*, *Add to Library Project* and new library projects, and a new
  source requires that version. *Create Settings* and *Ctrl+Space* derive the target from such a name.
- *Create Settings* and new library projects write a much shorter `settings.ahk-v2-plugin.ini`. `Banner` is always
  `@standard` or `@library`, and `scratch\=no` and `test\=no` are gone; put `Banner=find:banner.txt` and such lines into
  your user settings. *Create Settings* lists the main script of a tool in `[Install]`.
- `settings.ahk-v2-plugin.ini`: new files and the overview of *Deploy* have `[Cleanup]` before `[AddBanner]`.
- Deploy: binary files (`.exe`, `.dll` …; more types are known) no longer cause messages about the banner, and "Banner is
  set, but no deployed file gets it" is only a gray hint.
- Installing, updating or removing the plugin now requires a restart of the IDE. After an update without a restart, the
  IDE could lose the code style options of AutoHotkey.
- A line that starts with any binary operator (`+`, `|`, `=`, `:=`, `=>` ...) continues the previous line, also outside
  parentheses; before, this was marked as an error. Hotkeys such as `+a::` are still recognized.

### Fixed

- Deploy: if the old `settings.ahk-plugin.ini` has errors, the error message now offers *Update Settings*, too; before,
  the file could only be turned into `settings.ahk-v2-plugin.ini` when it had no errors.

## [2.2.1] - 2026-11-24

### Fixed: wrong version in compiled programs

- A compiled program could get the version of an included library instead of its own: Ahk2Exe also obeys
  `;@Ahk2Exe-SetVersion` in included files, and *Set Version* of 2.2.0 wrote this directive into every AutoHotkey file.
  Now *Set Version* and *Deploy* write the comment `;@SetVersion` into files without `;@Ahk2Exe-Base`. **Please deploy
  your libraries again, then compile and deploy your programs.**
- *Set Version*: the dialog no longer grows as wide as the screen in a project with many files.

### Added

- Manual and user guide mention [OpenInIDE](https://github.com/Kumpewo/open-in-ide), an optional Explorer context menu
  entry that opens the project of a deployed file or executable in IntelliJ IDEA.
- *Create Settings* in a folder below the library projects folder writes the settings of a library project; every
  AutoHotkey file of the project root is listed in `[Install]` with the target `{set target}`, to be replaced.
- *Set Version*: the message of commit and tag can have several lines (Ctrl+Enter sets the version).
- `settings.ahk-v2-plugin.ini`: *Ctrl+Q* shows the explanation of the option or section at the caret. A bar above the
  file offers *Remove Explanations and Compact* for a short file and *Restore Explanations*, which also adds deleted
  sections and options again, commented out. A new setting decides whether new files contain the explanations.
- The dialogs of *Set Version* and *Deploy* remember their size and scroll if the window is too small.

### Changed

- *Set Version* writes `;@Ahk2Exe-SetVersion` only into scripts with `;@Ahk2Exe-Base`; every other AutoHotkey file gets
  the comment `;@SetVersion`, also instead of an existing directive. *Add Ahk2Exe compiler directives* takes it over.
- *Deploy*: with `{version}` in the banner, the copy of a script without `;@Ahk2Exe-Base` loses its version line, like
  the copies of other file types.

## [2.2.0] - 2026-11-23

### Changed: please update your projects

- **The settings file is now `settings.ahk-v2-plugin.ini`, with new names:** `[Install]` with the options is now
  `[Deploy]`, `[Overwrite]` is now `[Install]`, `[KeepExisting]` is now `[InstallIfMissing]`, and
  `CompileBeforeInstall` is now `Compile`. The plugin still reads an old `settings.ahk-plugin.ini` and offers *Update
  Settings* in the confirmation of *Deploy*, which turns it into the new file. Version 2.5.0 will no longer read the old
  file.
- Environment variables such as `%APPDATA%` are no longer resolved in the settings file. Use the folder variables of
  AutoHotkey (`%A_AppData%`, `%A_MyDocuments%` ...), `%LIBRARY_PROJECTS%` or `%DESTINATION%`.
- In `[SetVersion]` a selector is now written like a line of `.gitignore`: `scratch\` matches such a folder at any
  depth, `/scratch\` only in the project root.
- *Set Version* sets the version in every AutoHotkey file of the project that `[SetVersion]` does not exclude and adds
  the line `;@Ahk2Exe-SetVersion` where it is missing (before, only files with the line).
- *Deploy* makes the copies of `[Install]` read-only (where the drive allows it) and overwrites read-only targets
  without asking; copies of `[InstallIfMissing]` stay writable. It stops if two lines give the same target.

### Added: library projects

- Set a *Library projects folder*, and each library gets a project of its own, with room for tests and Git; *Deploy*
  copies it into the Lib folder. The copies are read-only in the editor; a notification bar and *Open Library Project*
  lead back to the source line. The manual page *Library projects* explains why and how, step by step.
- *File > New > AutoHotkey Library Project...* (also on the welcome screen) and *AutoHotkey Library File*; *Create
  Library Project* and *Add to Library Project...* turn an existing library file into a project or add it to one.
- One project can deploy several versions of a library (for example for v1 and v2); projects may be organized in
  subfolders.

### Added: Deploy and Set Version

- `Banner` in `[Deploy]` writes a "do not modify" banner at the start of the copies: your own text, a built-in template
  (`@standard`, `@library`) or a file (`file:<path>`, or `find:<name>` in the project folder or above), with
  placeholders such as `{project-name}`, `{version}`, `{commit}`, `{timestamp}` and `{include}`. AutoHotkey files get it
  automatically, other files by `[AddBanner]`. New settings files use a `banner.txt` found in the project folder or
  above.
- The built-in banners name the computer, and deployed executables carry project folder and computer as text at their
  end, so that a tool can find the project of an `.exe`; `EmbedOrigin=no` in `[Deploy]` leaves both out.
- `[Cleanup]` removes leftovers of earlier deployments. A second confirmation lists every file; they are moved to the
  Recycle Bin.
- Renaming or moving a file or folder of the project updates the settings file; if the deployed name depends on it, a
  dialog asks whether the deployed file is renamed too.
- *Create Settings*, *Set Version* and *Deploy* are available in every project, also without AutoHotkey files. They know
  the comments of many file types (e.g. `.py`, `.sh`, `.yaml`, `.java`, `.sql`, `.cmd`, `.reg`).
- `[SetVersion]` can include files of other types as well; `[SetVersion]` and `[AddBanner]` take the values `yes`, `no`,
  `line:<start> [<end>]` or `block:<start> <end>`, and the last matching line wins.
- A new `settings.ahk-v2-plugin.ini` takes its commented-out examples from the files in the project root.

### Added: editor

- *Edit > Fill Paragraph* wraps the paragraph at the caret in `;` comments, block comments and doc comments; the code
  style page has its own *Hard wrap at* for comments.
- Doc comments: unknown tags (typos such as `@retruns`) are marked, with a quick fix; `@author`, `@since`, `@version`,
  `@todo`, `@license` and `@copyright` are accepted. Completion of tags and of the parameters after `@param`.
- `@deprecated` also for properties and fields; uses of deprecated definitions are an inspection, so *Code > Inspect
  Code* lists them for a whole project.
- Files with the extensions `.ah2` and `.ahk2` are AutoHotkey files too.
- Old ANSI files: a notification bar above a text file that is not valid UTF-8 offers *Convert from ANSI to UTF-8*.

### Changed

- The switches of `[Deploy]` (`RequiresAdmin`, `GenerateOverview`, `Compile`) take `yes` or `no`; `true` and `false`
  still work.
- *Set Version* proposes versions also when no file has one yet, lets you edit the proposed version, and says why if
  there is no file to change.
- *Add Ahk2Exe compiler directives* takes over an existing line `;@Ahk2Exe-SetVersion`.
- `;@declare-type` and `;@declare-auto-type` color only variables, not names that are called; the wildcard of
  `;@declare-auto-type` may also stand inside the class pattern, e.g. `Gui.*Box`.
- `yes` writes the comment of a `.js` file with `//` instead of `/* */`.
- The AutoHotkey entries of the *Code* menu and the context menus are grouped by separators; library project actions
  have the green AutoHotkey icon, project actions and the manual the plugin logo.
- If Ahk2Exe is missing, the message says how to install it.

### Fixed

- The link to the external help at the bottom of the documentation popup did nothing; it now opens the topic like
  *Shift+F1*.

## [2.1.0] - 2026-11-18

### Added

- Color *Declaration > Hotstring replacement text* for the text after `::` (like a string by default); escape
  sequences in it get the escape color.
- Color *Special > Key in send text* for the keys in hotstring replacement text and in the strings passed to
  `Send`, `SendInput`, `SendEvent`, `SendPlay` and `ControlSend` (`{Enter}`, `{Tab 3}`, `^c`,
  `{Raw}`); unknown key names in braces (`{Entr}`) get a warning, because AutoHotkey ignores them silently.
- Keys in `settings.ahk-plugin.ini` may now be enclosed in double quotes, like values.
- Section `[SetVersion]` in `settings.ahk-plugin.ini`: lines `path=no` (e.g. `scratch\=no`) keep files and
  folders out of Set Version. A mistake in the settings only stops the action it concerns.

### Changed

- Deploy, Set Version and Create Settings follow the rule "one tool = one project" and always work on the whole
  project, whatever file is open; their menu entries name the project.
  - `settings.ahk-plugin.ini` lies in the project root; sources are relative to it. The section `[Version]`
    (`TagPrefix`) is gone.
  - Deploy compiles what it copies: every executable of `[Overwrite]` and `[KeepExisting]` that has a script of
    the same name next to it (e.g. a GUI and a console variant). `CompileBeforeInstall` is now on by default;
    `CompileBeforeInstall=no` copies the executables as they are.
  - Set Version sets the version in every script of the project with a line `;@Ahk2Exe-SetVersion`, commits all
    changes of the project and sets the tag `v<version>`; commit and tag need every file to be versioned or ignored.
    It needs no `settings.ahk-plugin.ini`.
  - Create Settings writes the file into the project root and is only offered while there is none.
- Validate and Compile use the script of the selected AutoHotkey run configuration from any file, also from a
  script it does not include; without a run configuration the current file.
- Menu order: Validate, Compile, Create Settings, Set Version, Deploy, followed by a separator.
- Validate, Compile, Deploy and Set Version save all modified files first, not only AutoHotkey files (e.g. an
  edited `settings.ahk-plugin.ini` is deployed and committed as shown in the editor).
- Quick documentation: the first column of tables in the AutoHotkey help (e.g. the built-in variables) no longer
  breaks short names such as `A_ScriptFullPath`.
- The menu entries Validate and Compile name their target, e.g. *Validate 'Main' with AutoHotkey* with the
  selected run configuration or *Validate 'tool.ahk' with AutoHotkey* with the current file. *Create
  settings.ahk-plugin.ini* is now called *Create Settings*.
- Menu entries (Validate, Compile, Create Settings, Set Version, Deploy, Attach to AutoHotkey Script, the manual and
  the help) and the AutoHotkey run configurations show the icon of the AutoHotkey interpreter instead of the file
  icon.

## [2.0.0] - 2026-11-17

### Added

- First support for AutoHotkey v2.1 (alpha): each file has a language level, v2.0 or v2.1, from its `#Requires`
  (a version of 2.1 or later means v2.1); files without `#Requires` use the new setting *Language level*
  (default v2.0). In v2.1 files the new built-in names of v2.1-alpha.39 are highlighted and completed, with
  parameter info, and the popup help shows the pages of v2.1-alpha.39.
- First v2.1 syntax: the operators `!~=`, `??=` and `a?.b` (optional chain) and struct definitions
  (`struct Name { ... }`); in v2.0 files they are errors "Requires AutoHotkey v2.1".
- Mandatory parameters after optional ones (`f(a?, b)`) are allowed in v2.1 files; in v2.0 files they are now
  reported as an error "Parameter default required", as AutoHotkey v2.0 does.
- Modules: `#Module Name` and `#Import [Export] Name [as Alias] [{ *, Name [as Alias], ... }]` (also across
  lines inside the braces) are parsed, formatted and checked (where they are allowed, the module name); in v2.0 files
  they are errors "Requires AutoHotkey v2.1".
- Module namespaces in v2.1 files: each `#Module` (and `__Main`) has its own functions and classes for navigation,
  Find Usages, Rename, completion, parameter info and highlighting; `#Import` with module name, alias, list and
  `{*}` (also passed on with `Export`) makes names visible; `X.Name` goes to what module X offers (also its global
  variables), `AHK.Name` to the help. Module files are found in the search path of AutoHotkey (folder of the file,
  `AhkLibPath` or the user and standard library; `Name`, `Name\__Init.ahk`, `Name.ahk`, `"Path:Name"`); Ctrl+B on
  the module name opens the `#Module` or the file. Find Usages and Rename also search files that import the
  definition's file; `#Module` names and aliases can be renamed. Completion after `#Import`, in its braces and
  after `X.`. A built-in function a module defines itself is no longer colored as built-in. Errors "Module not
  found" and for an import that conflicts with an own definition, a warning for a name the module does not have.
  The structure view groups the definitions by module; `#Module` sections can be folded.
- `AhkLibPath` (environment variable of the IDE) is used for `#Include <Name>` in v2.1 files, as in AutoHotkey.
- Function definition expressions (`x := f(a) { ... }`, `SetTimer(() { ... }, 100)`): parsed and formatted like
  functions, with navigation, completion, parameter info and local variables (closures); in v2.0 files they are
  errors "Requires AutoHotkey v2.1".
- Typed properties in classes and structs (`x : Int32 := 0`, `x : Int32, y : Int32`, `union { ... }`): parsed,
  formatted and checked; they are fields of the class for navigation, completion, Find Usages and Rename. In v2.0
  files they are errors "Requires AutoHotkey v2.1".
- `Throw` as a function in expressions (`x := c ? 1 : throw(Error())`, `f := Throw`); in v2.0 files an error
  "Requires AutoHotkey v2.1".
- v2.1 files are run, validated and debugged with the AutoHotkey v2.1 interpreter, never through the launcher
  (which does not know an alpha that was only unpacked): new setting *AutoHotkey v2.1 interpreter*, empty = the
  newest installed version 2.1 or later (version folders such as `v2.1-alpha.39`, or listed by the launcher).
  Shift+F1 in v2.1 files opens the `AutoHotkey.chm` of that interpreter.
- `#Include <Name>` in v2.1 files is resolved as AutoHotkey v2.1 does it: the directory of the file first, the
  exact name before `Name.ahk`, no special meaning of `_`, the standard library next to the v2.1 interpreter.
- Debugger with AutoHotkey v2.1: expanding large arrays, maps and objects loads every child (the v2.1 engine also
  lists inherited properties and does not count the children).
- The language level of the current file is shown in the status bar (`AHK v2.1`; click = go to `#Requires` or open
  the settings) and in grey after AutoHotkey files in the Project view (can be switched off in the settings).
- *Alt+Enter* offers to insert `#Requires` anywhere in a file without `#Requires`, not only in the first line. The
  line matches the language level: with the default level v2.1 it is the version of the v2.1 interpreter
  (`#Requires AutoHotkey v2.1-alpha.39`, otherwise `v2.1-`).
- *Help > AutoHotkey v2.0 Help* and *Help > AutoHotkey v2.1 Help* (formerly *AutoHotkey Help*) open the help of
  each version; without an installed `AutoHotkey.chm` they open the copy of the help that ships with the plugin in
  the browser.
- *Code > Deploy to Local Target...*: copies a tool to a fixed folder as described by a `settings.ahk-plugin.ini`
  next to the script (`[Install]`, `[Overwrite]`, `[KeepExisting]`; targets with `%DESTINATION%` and environment
  variables, wildcards, copying with administrator rights), optionally after compiling it. Everything is checked and
  confirmed before anything happens.
- *Code > Set Version...*: changes the version in the line `;@Ahk2Exe-SetVersion` of the script (next patch, minor,
  major or any version), commits only this file and sets the tag `<TagPrefix>/v<version>` (prefix from `[Version]`
  of `settings.ahk-plugin.ini`); commit and tag are optional, Git is not required. New manual page "Deploying tools
  and setting versions".
- *Code > Create settings.ahk-plugin.ini*: writes a documented default file with all options commented out.

### Changed

- The head of compiler directives (*Alt+Enter > Add Ahk2Exe compiler directives*) now sets the version with
  `;@Ahk2Exe-SetVersion` (the line that *Set Version...* changes) and writes product name, description and
  copyright directly, aligned and without quotes (Ahk2Exe would keep them in the executable), instead of script
  variables read with `A_PriorLine`.
- Manual: new chapter "AutoHotkey v2.1 (alpha)" with everything about v2.1 (language level, new syntax, validation
  with the alpha, known limitations), modules as its sub-page, and a notice on the start page that v2.0 is fully
  supported while v2.1 support is in development.

### Fixed

- Find Usages and Rename of a field now also work at its definition (`count := 0`, a typed property `left : Int32`),
  not only at a use such as `this.left`.
- The structure view shows the fields (typed ones with their type) and properties of a class.
- `struct` is no longer treated as a keyword: in AutoHotkey v2.0 it is an ordinary name and does not define a class.

Forms from the AutoHotkey help examples that AutoHotkey accepts, but the plugin marked as errors:

- Continuation sections outside a string (`MsgBox(⏎(⏎"a: " x "⏎b"⏎))`, `Var :=⏎(⏎…⏎)`, options like
  `( LTrim Join`), with strings spanning lines; a continuation section as the replacement text of a hotstring.
  *Reformat Code* leaves continuation sections unchanged.
- `this` as a parameter name (`(this) => …`, `f(this) { }`, `this => …`).
- Member names starting with a digit (`m.0`, `obj.1a`).
- Function call statements whose first argument starts with `!`, `~`, `&`, `++`/`--` or an object literal
  (`SetCapsLockState !x`, `ToolTip ++n`, `WinActivate {Hwnd: id}`, `WinGetClientPos &X, &Y,,, "A"`).
- Dynamic property names in object literals (`{%name%: value}`).
- `*/` in the middle of a line no longer ends a block comment (only at the start or end of a line).
- `static` alone as the first statement of a function (assume-static).
- Code after `{` when the brace starts its line or follows `else`/`try`/`finally` (`if x⏎{ MsgBox "a"`), and code
  after a closing `}` on the same line.
- A ternary whose `:` ends the line, with the last operand on a following line (`x := a ?⏎b :⏎c`).
- A line starting with `.Name` continues a method chain also outside parentheses
  (`wmi.ExecQuery(…)⏎._NewEnum()`).
- Hotkeys for a quote key (`^+"::`, `'::`).
- Named fat arrow functions in expressions (`sumfn := Sum(a, b) => a + b`); they are parsed as functions.

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
