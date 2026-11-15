# User Guide

This guide describes what the AutoHotkey v2 plugin does in the editor and how to configure it.
The plugin also contains a more detailed manual with examples: *Help > AutoHotkey Plugin Manual* (or the link at
the bottom of *Settings > Languages & Frameworks > AutoHotkey*).
Shortcuts are given for the default Windows keymap of IntelliJ IDEA.

- [Getting started](#getting-started)
- [Syntax support and error highlighting](#syntax-support-and-error-highlighting)
- [Highlighting](#highlighting)
- [Code completion](#code-completion)
- [Parameter info](#parameter-info)
- [Navigation](#navigation)
- [Help](#help)
- [Type declarations](#type-declarations)
- [Formatting and case adjustment](#formatting-and-case-adjustment)
- [Live templates and file template](#live-templates-and-file-template)
- [Running scripts](#running-scripts)
- [Settings overview](#settings-overview)
- [Known limitations](#known-limitations)

## Getting started

1. Install the plugin: *Settings > Plugins > Marketplace*, search for "AutoHotkey" (plugin page:
   [AutoHotkey v2 Language Support](https://plugins.jetbrains.com/plugin/36153-autohotkey-v2-language-support)).
2. Open a folder with `.ahk` files as a project, or create a script with *File > New > AutoHotkey Script*.
3. Optionally check *Settings > Languages & Frameworks > AutoHotkey*. The plugin detects a standard
   AutoHotkey installation by itself; the path is only needed for unusual locations.

Every script should start with `#Requires AutoHotkey v2.0`. Without it the plugin shows a weak warning on the
first line; *Alt+Enter* inserts the line. If AutoHotkey is installed, the syntax detection of its launcher checks
such files in the background; for code that looks like AutoHotkey v1 the hint becomes a warning and *Alt+Enter*
offers `#Requires AutoHotkey v1.1` first.

## Syntax support and error highlighting

The plugin parses AutoHotkey v2 and marks syntax errors in the editor. Errors found by the grammar are simply
shown as "Syntax error"; specific rules below have their own messages. A few rules worth knowing:

- **The end of a line ends a statement.** A line continues only after an operator at the end of the line,
  before a line that starts with `and`, `or`, `&&`, `||`, `??`, `?`, `:`, `.`, `,` and similar, inside
  parentheses (there also before a line that starts with any binary operator such as `+`, `*`, `=`, `:=` or with
  the dot of a method call `.Method()`), and in continuation sections (`"` at the end of a line, then `(` ... `)"`).
- **Block comments** `/* ... */` are only recognized at the start of a line (also indented). Elsewhere they
  are marked as an error, because AutoHotkey does not recognize them there.
- After an opening `{` of a block nothing may follow on the same line except a comment.
- `else` followed by a statement on the same line is accepted, but gets a warning (hard to read).
- `global` without a name is only allowed as the first statement of a function, method, hotkey or hotstring
  block.
- A label `Name:` must stand alone on its line.
- Files that require another AutoHotkey version (`#Requires AutoHotkey v1.1`) are not parsed at all; there
  is only a warning at the `#Requires` line.

### Suppressing parser errors for one line

The parser does not know every construct (for example `SetTimer () => MsgBox("x"), 100`, a call without
parentheses whose first argument is a function). Put a marker comment before such a line:

```autohotkey
;@ignore-next-line the parser does not support this call form
SetTimer () => MsgBox("x"), 100
```

The next line is still parsed, but its parser errors are hidden and the line is shown with a marked
background. The marker comment itself gets a warning ("Parser errors suppressed."), or a hint if the next line
has no error at all. The marker applies to exactly one line; follow-up errors in later lines stay visible.

## Highlighting

Colors are configured under *Settings > Editor > Color Scheme > AutoHotkey*, in four groups:

| Group | Color | Examples |
|-------|-------|----------|
| Core | Flow control | `if`, `loop`, `return`, `switch`, `case`, `default` in a switch, `Files`/`Parse`/`Read`/`Reg` after `Loop` |
| Core | Reserved word | `and`, `or`, `not`, `is`, `in`, `local`, `global`, `static`, `class`, `extends` |
| Core | Reserved value | `true`, `false`, `unset`, `this`, `super` (bold purple by default) |
| Core | Directive | `#Include`, `#SingleInstance`; unknown directives get a warning |
| Core | Number, String, Label, Comment | `42`, `"text"`, `Retry:`, `; note` |
| Declaration | Function, Class | the name in `Next() {` and `class Counter` |
| Declaration | Hotkey, Hotstring | `^!r::`, `::btw::` |
| Built-in elements | Function, Variable, Class | `MsgBox`, `A_Index`, `Gui`, also as parameters and in declarations; nested classes `Gui.Button`, `extends Gui.Edit` |
| Built-in elements | Method, Property | `Push`, `Length` - only as a whole name after a dot |
| Special | Escape sequence in string | `` `n ``, `` `t ``, `` `" `` in strings |
| Special | Variable with declared type | names covered by `;@declare-type` |
| Special | Declared private member | members covered by `;@declare-private` |
| Special | Unchecked code line | the line after `;@ignore-next-line` (marked background) |

A keyword used as a name after the dot (`obj.if`) is an error.

## Code completion

Completion opens automatically while typing letters, after `.` and after `#`, or with *Ctrl+Space*. Case
does not matter while typing; the spelling of the list is inserted. Functions and methods are inserted with
`()` and the caret between the parentheses.

What is offered depends on the position:

- **Directive** (after `#`): all known directives.
- **Free-standing name**: inside a function first its [local variables and parameters](#local-variables-and-parameters)
  (also those it shares with enclosing functions); built-in variables, classes and functions; own functions and
  classes of the file and of the files it includes directly with `#Include`. At the start of a statement also the flow control
  keywords; directly after `Loop` also `Files`, `Parse`, `Read`, `Reg`.
- **After the dot**: methods and properties of the built-in classes; methods, properties and fields (`count := 0`
  in a class body) of the own classes. After the name of
  a built-in class like `Gui.` its nested classes come first. For a name with a [declared type](#type-declarations)
  only the members of that type and its base classes are offered, nearest class first. Meta members like
  `__New` or `__Item` are never offered.
- **After `goto`, `break`, `continue`**: the labels of the same function or hotkey.
- **Parameter values**: in a string argument (`CoordMode "Mo|"` offers `Mouse`), at a free argument position
  with quotes (`CoordMode Mo|` inserts `"Mouse"`), and in directives without quotes
  (`#SingleInstance Fo|`). For `Gui.Add(` the control types are offered, for `OnEvent(` the event names (per control
  type if the type is declared).
- **Option strings**: the word at the caret in the options of `MsgBox` (`"YesNo Ic|"`), `Gui()`/`Gui.Opt`
  (`"+Res|"`, the sign stays), `Gui.Show`, `Gui.Add`/`Gui.AddXxx` (common options plus those of the control type:
  `MyGui.Add("Edit", "w200 Read|")`) and `Opt` of a control with a declared type. Options with a value (`w200`,
  `vName`) insert only their letters (`names/options.txt`).
- **Key names**: inside a string of `Send...`/`ControlSend...` after `{` (the closing `}` is added), and in a
  hotkey directly before `::` (type `::` first, put the caret before it, then start the key name).
- **`#Include` paths**: after `#Include <` the `.ahk` files (without extension, the closing `>` is added) and
  subdirectories of the library folders (`Lib` next to the script, the user library, the standard library). Otherwise
  the `.ahk` files and subdirectories of the directory relative paths refer to: the directory of the file, or the
  directory of the last earlier `#Include Directory` line. Quotes, `*i ` and a typed directory part
  (`sub\`, `..\`, `%A_ScriptDir%\`, absolute paths) are understood; a directory is inserted with `\` and the
  popup opens again. The popup also opens after `<`, a quote, `\` or `/` in an include line.

There is no completion in comments, other strings, and at definitions (function, class and parameter names,
object keys).

Typing `"` or `'` inserts the closing quote; typing `%` in code inserts the closing `%` of a dynamic
reference.

## Parameter info

*Ctrl+P* (and automatically after typing `(` or after completing a function) shows the parameters of the
call at the caret, with the current argument highlighted. It works for

- calls `f(a, b)`, commands without parentheses `f a, b` and directives `#Warn All, Off`;
- own functions of the file and of directly included files (they take precedence over a built-in function of
  the same name);
- method calls `obj.Method(...)`: forms of the own classes, plus the built-in forms. If the class of `obj` is
  known (a built-in class like `Gui.` or a [declared type](#type-declarations)), only the forms of that class
  and its base classes are shown, otherwise all forms are shown with their class (`Gui.Add(...)`,
  `Menu.Add(...)`).

The command form of a method call (`obj.Method "x", 1`) has no parameter info.

## Navigation

*Ctrl+B* or *Ctrl+Click* on a name jumps to its definition. There is no type analysis, so the plugin matches
names only (case-insensitive) and offers a chooser if there are several matches - first the current file,
then the included files in the order of their `#Include` lines.

- **Free-standing name** (`DoWork()`, `Counter.count`): functions outside of classes and top-level classes; inside
  a function a [local variable or parameter](#local-variables-and-parameters) leads to its definition.
- **Name after the dot** (`obj.Next()`): methods, properties, fields and nested classes of all classes.
- **Base class** after `extends`: the class; `extends Outer.Inner` resolves step by step.
- **Labels**: `goto Target`, `goto "Target"`, `break Outer`, `continue Outer`, within the same function or
  hotkey.
- **`#Include`**: opens the included file. `<Name>` is searched in the library folders (`Lib` next to the
  script, `Documents\AutoHotkey\Lib`, `Lib` of the AutoHotkey installation); `%A_ScriptDir%` and other common
  built-in variables are resolved. If the argument is a directory, a hint is shown.
- **Built-in names** without an own definition open the quick documentation.

The search covers the file itself and the files it includes directly with `#Include` (not recursively).

The global library folders are added to the project as the library *AutoHotkey Lib* (*External Libraries* in
the project view), so files opened from there are fully highlighted. Files outside the project that are not in
a library folder only get the basic lexer highlighting; add their folder as a content root if needed.

### Local variables and parameters

Inside a function (also a method, a property, `get`/`set`, and a fat-arrow function `(x) => x * 2`) the plugin
knows which names are local, following the rules of AutoHotkey v2:

- **Local** are the parameters, names declared with `local` or `static`, and names that are assigned (`x := 1`,
  `x += 1`, `x++`), passed by reference (`&x`) or used as `for` and `catch ... as` variables - anywhere in the
  function, as AutoHotkey decides this when the script loads.
- **Not local** are names that are only read (they may be global variables), names declared `global`, all names
  that are only assigned in an assume-global function (`global` alone as the first line), built-in variables, and
  the names of functions and classes (built-in or own) unless they are parameters or declared `local`/`static`.
- **Nested functions** share the variables of the enclosing function that it has as parameters, declares or
  assigns, unless they declare the name themselves; a `static` nested function only shares `static` variables. A
  `global` declaration in the enclosing function applies to the nested functions as well.

For such a variable, *Ctrl+B* leads to its first definition (parameter, declaration or first assignment);
completion, *Find Usages*, *Rename* and the highlighting of occurrences only look at the same variable, so a
`count` in another function or a global `count` is not affected. Variables outside of functions (global variables)
still work by name.

The structure view (*Alt+7*) lists hotkeys and hotstrings, classes, functions and labels. Breadcrumbs and
sticky lines show the enclosing class, function, hotkey and control structures.

### Find Usages

*Alt+F7* on a function, method, class, property, field or label (at its definition or at a use) lists its usages.
For a local variable or parameter only its function is searched (see
[Local variables and parameters](#local-variables-and-parameters)).
Searched are the file of the definition and all AutoHotkey files of the project that include it with a direct
`#Include` - exactly the places from which *Ctrl+B* leads to the definition. The matching follows navigation: by
name, ignoring case, so a free-standing variable with the name of a function counts as well. *Ctrl+B* on a
definition shows the same list.

### Rename

*Shift+F6* on a function, method, class, property, field or label (at its definition or at a use) renames it, always
with a **preview**: all occurrences appear in the Find tool window; exclude those that belong to something else
(*Delete* or *Exclude*, also whole files), then *Do Refactor*. *Ctrl+Z* undoes the rename in all files.

- Searched are the file of the definition and all AutoHotkey files of the project that include it, directly or
  indirectly. Occurrences are found by name, ignoring case (as for *Find Usages*), and get the new spelling.
- *Search in comments and strings* adds whole-word occurrences in comments and strings of these files.
- The new name must be a valid AutoHotkey name and no reserved word; an existing function or class (or member of
  the same class) with that name is reported as a conflict.
- **Local variables and parameters** are renamed directly in the editor, without dialog and preview: type the new
  name, *Enter* applies it to all occurrences of the variable in its function (and in nested functions that share
  it) and, for a parameter, to its name after `@param` in the doc comment; *Escape* cancels. Global variables are not renamed.
- Where nothing can be renamed (a global variable, a built-in name, a keyword, a member without an own definition),
  a hint at the caret says why; for global variables it suggests *Edit > Find > Replace in Files*.

Check the preview: which class a call like `obj.f()` reaches is only decided at run time (`obj` may be an `X` in
one call and a `Y` in the next, both with a method `f`), and dynamic names (`obj.%name%()`, `HasMethod(obj, "f")`)
or scripts that are not connected by `#Include` cannot be found. Validate the main script afterwards: a call of a
missing function is a load error in AutoHotkey v2.

### Go to Symbol and Go to Class

*Navigate > Symbol* (*Ctrl+Alt+Shift+N*) searches the definitions of **all** AutoHotkey files of the project by
name: functions, methods, classes, properties, fields, labels, hotkeys and hotstrings. *Navigate > Class*
(*Ctrl+N*) lists only the classes; both are also tabs of *Search Everywhere* (*Shift* twice).

- Type the name, a part of it or its capitals, ignoring case; members and nested classes also with their class
  (`Box.Open`, `Outer.Inner`).
- Entries show the name with its classes (`Box.Open()`, `Box.count`, `Start:`, `F1::`, `::btw::`) and the file. For
  a hotkey type its keys (`F1`), for a hotstring its abbreviation (`btw`).
- The library *AutoHotkey Lib* is searched only with *Include non-project items*.

Unlike *Ctrl+B* this is a free search: a function with the same name in another script appears as well, and you
choose the entry. Go to declaration still only looks at the file and its directly included files.

### Highlighting occurrences

When the caret rests on a name, all occurrences of that name in the file are highlighted (also with
*Ctrl+Shift+F7*; *F3*/*Shift+F3* move between them). As for navigation there is no type analysis: every name
with the same spelling counts, ignoring case - variables, functions, classes, members after the dot and labels
alike. Only for a local variable or parameter of a function just the occurrences of that variable count, and the
local variables of functions are left out for any other name. Definitions and assignment targets (`x := 1`, `obj.x += 1`, parameters, declarations, `for` and
`catch ... as` variables, function, class and property names, labels) are shown as write access, all other
occurrences as read access. Names in strings and comments don't count.

## Spell checking

The spell checker of the IDE (*Settings > Editor > Inspections > Proofreading*) checks comments, strings and the
replacement text of hotstrings. Escape sequences (`` `n ``) are skipped, pragma and compiler directive comments
(`;@declare-type`, `;@Ahk2Exe-...`) and names in the code are not checked. Built-in names and their parts
(`MsgBox`, `LoopField`), key names and terms like *hotkey* or *hotstring* are known words (dictionary
*AutoHotkey*).

## Code folding

The +/- marks in the gutter (and *Ctrl+NumPad -*/*+*) fold, if they span several lines:

- everything between braces: classes, functions, methods, properties, control structures, hotkey and hotstring
  blocks, `switch` blocks, object literals (`{...}`);
- block comments (`/*...*/`) and continuation sections (`"(...)"`);
- runs of two or more comment lines (shown as the first line) and of two or more `#Include` lines;
- custom regions between `;region Name` and `;endregion`, or between `;{ Name` and `;}` (SciTE4AutoHotkey style),
  shown with their name.

Nothing is folded when a file is opened.

## Help

- **Quick documentation** (*Ctrl+Q* or mouse hover) shows the help page for a built-in name or directive,
  and anywhere in a pragma comment (`;@declare-type`, `;@declare-auto-type`, `;@ignore-next-line`) the page
  of that pragma. Links in the page open further pages in the same popup. For a member that exists in several classes (for
  example `Add` of `Gui` and `Menu`), the popup shows a list of the variants; if the class is known (see
  [type declarations](#type-declarations)), only the matching variant is shown.
- **External help** (*Shift+F1*) opens the topic in an external viewer. By default this is `hh.exe` with the
  `AutoHotkey.chm` of the AutoHotkey installation. A different program can be configured under
  *Settings > Languages & Frameworks > AutoHotkey > External help*; `%s` is replaced by the key of the help
  page (e.g. `/docs/lib/MsgBox.htm` or `/docs/lib/File.htm#Close`). Help pages added by the plugin itself
  (the pragmas) always open in `hh.exe` with the plugin's own help file, which is copied to the temporary
  directory (`intellij-autohotkey\extra-….chm`); the configured command line does not apply to them.
- **Versions:** the popups show help pages that are part of the plugin, currently those of AutoHotkey v2.0.31.
  The external help uses the `AutoHotkey.chm` of the installation and therefore matches the installed version.
- **Compiler directives:** in a comment `;@Ahk2Exe-...` *Ctrl+Q* and *Shift+F1* show the page "Script Compiler
  Directives" of the AutoHotkey help.
- **AutoHotkey help:** *Help > AutoHotkey Help* opens the help of the installation at its start page (with a
  configured external help command line, that one with the key `/docs/index.htm`).
- **Plugin manual:** *Help > AutoHotkey Plugin Manual* opens the manual of the plugin in `hh.exe`; if the plugin
  was built without its help file, the pages are copied to `%TEMP%\intellij-autohotkey\manual\` and opened in the
  browser.

### Doc comments

A block comment `/** ... */` directly before an own function, method or class (spaces and one line break in
between, no empty line) documents it, like JavaDoc or JSDoc:

```autohotkey
/**
 * Opens the box and counts the openings.
 * @param {String} name  name of the box
 * @param force  open even if the box is locked
 * @returns {Integer} the new number of openings
 * @see CloseBox
 */
OpenBox(name, force := false) {
    ...
}
```

- The leading `*` of each line is removed; the text before the first tag is Markdown.
- Tags: `@param [{Type}] name text`, `@returns [{Type}] text`, `@throws text`, `@example` (code below it),
  `@see name|url [text]` (link to an own definition, the AutoHotkey help or a web page), `@deprecated [text]`.
- *Ctrl+Q* or hover on the definition or any use shows the signature, the description, a section per tag and the
  file. The definitions are found by name like *Ctrl+B*; an own definition takes precedence over built-in help of
  the same name, several matching definitions are listed with links. *Ctrl+hover* shows the signature.
- The name after `@param` belongs to the parameter: *Ctrl+B* on it leads to the parameter, renaming the parameter
  renames it as well, and *Find Usages* and the highlighting of occurrences show it.
- The doc comment also appears for *Ctrl+Q* in the completion list, and the parameter info shows the `@param`
  text of the current parameter.
- `@param {Gui} g` works like `;@declare-type g as Gui`, but only inside that function (several types with `|`).
- Uses of a definition marked `@deprecated` are struck out (only if all definitions with that name are
  deprecated).
- `/**` + *Enter* above a definition adds the skeleton with one `@param` per parameter and `@returns` if the
  function returns a value; *Enter* inside a doc comment continues with ` * `. A `@param` without such a
  parameter and an unknown class in `{...}` are marked.

## Type declarations

AutoHotkey has no type declarations, so the plugin cannot know that `win` is a `Gui`. A comment pragma tells
it. The pragma applies to the whole file in which it stands, wherever it is placed.

```autohotkey
;@declare-type win as Gui, *Edit as Gui.Edit
win := Gui()
nameEdit := win.AddEdit()
nameEdit.   ; completion offers the members of Gui.Edit, Gui.Control, Object and Any
```

- `;@declare-type pattern as Class, pattern as Class, ...`
  - The pattern is a name (`win`), a prefix (`btn*`) or a suffix (`*Edit`); the wildcard also matches nothing.
  - The class is a built-in class (also nested, `Gui.Edit`) or an own class of the file or of a directly
    included file (also nested, `Outer.Inner`).
- `;@declare-auto-type ClassPattern, ...` declares a suffix pattern per matching built-in class:
  `;@declare-auto-type Gui*` covers e.g. `mainGui` as `Gui` and `okButton` as `Gui.Button`; `*` covers all
  built-in classes.

Declared names get their own color and narrow completion, parameter info and help variants to the declared
class and its base classes. A declaration with a wildcard does not override the color of a built-in name;
an exact name does.

Mistakes in a pragma are marked in the comment ("Unknown class.", "Invalid pattern.", "'as' expected." ...).

### Private members

AutoHotkey v2.0 has no private members. `;@declare-private Pattern, ...` (name, `prefix*` or `*suffix`) shows
members with matching names in the color *Private member* (gray and italic by default): method, property and field
definitions in classes and names after the dot (`this._count`). Free-standing names are not affected. A built-in
member name keeps its built-in color unless it is declared by its exact name. Names may use any character
AutoHotkey allows, also non-ASCII ones such as `§` (`;@declare-private §*`); the platform inspection "Non-ASCII
characters" does not report names in AutoHotkey files.

### Pragmas for a whole project: pragmas.ahk

Pragmas apply to their own file. The `;@declare-type`, `;@declare-auto-type` and `;@declare-private` pragmas of a
file named `pragmas.ahk` also apply in every file that includes it directly (`#Include <pragmas>`,
`#Include <v2\pragmas>`, `#Include ..\shared\pragmas.ahk`). Own classes in its `;@declare-type` lines are looked
up in the including file.

## Formatting and case adjustment

*Code > Reformat Code* (*Ctrl+Alt+L*) formats the file or the selection. Options are under
*Settings > Editor > Code Style > AutoHotkey*:

- indent and continuation indent (default 4 and 8); all continuation lines of a statement get the same
  continuation indent, also for nested expressions (`a +⏎b *⏎c`);
- spaces around operators, after commas, inside parentheses, brackets and braces;
- `case` is aligned with `switch`, its statements one level deeper;
- block comments get the indent of their place; in a doc comment `/** ... */` the lines starting with `*` follow
  the first line (the stars stand below each other).

The tab *Doc Comments* rewrites doc comments on reformat (main switch *Format doc comments when reformatting*,
default off), with switches like the JavaDoc settings of IntelliJ (defaults in brackets):

- Alignment: parameter descriptions in one column (on), types and names in columns (on), indent continuation
  lines (off), parameter descriptions on a new line (off).
- Blank lines (on = exactly one, off = none): after the description (on), after the parameters (off), after
  `@returns` (off), before `@example` (off).
- Other: wrap at the right margin (on; *Hard wrap at* under *Editor > Code Style > General*, default 120), keep
  one-line comments (off), preserve line feeds (off), leading asterisks (on).
- Tags: keep `@param` without a parameter (on), keep empty tags (on), sort `@param` (off), add missing `@param`
  (off).

`@example` code, Markdown code blocks, headings, tables and hard line breaks are never changed; list items are
wrapped below their text; words (and URLs) are never split.

Line breaks are never added or removed, because the end of a line ends a statement. The space before `(` or
`[` in calls is never changed either (`f (x)` is a concatenation, not a call).

### Case adjustment

On the tab *Case* of the code style page you can let *Reformat Code* adjust the case of known words:

- **Adjust case when reformatting** - main switch, off by default.
- **Built-in names** (variables, functions, classes, members, directives): *as in list* or *keep*.
- **Keywords**, one switch per group: *as in list*, *lowercase* or *keep*. The groups and their spellings come
  from `names/keyword-case.txt` in the plugin.

Only words in their recognized role are changed (a property named like a function stays as it is), and never
strings, comments, hotstrings, own names, or the line after `;@ignore-next-line`. The keyword spelling is also
used by completion.

## Live templates and file template

Type an abbreviation and press *Tab*. The templates are listed under *Settings > Editor > Live Templates >
AutoHotkey* and only apply in code (not in comments or strings).

| Abbreviation | Inserts |
|--------------|---------|
| `req`, `si`, `hotif` | `#Requires AutoHotkey v2.0`, `#SingleInstance`, `#HotIf` block |
| `hk`, `hs`, `hsx` | hotkey with block, hotstring, hotstring that runs code |
| `fn`, `cls`, `clse`, `prop`, `arrow` | function, class, class with base class, property, fat arrow function |
| `if`, `ife`, `while`, `loop`, `lf`, `lp`, `lr` | if, if/else, while, loop, Loop Files / Parse / Read |
| `for`, `forv`, `try`, `sw` | for with key and value, for with value, try/catch, switch |
| `gui`, `timer`, `onexit` | Gui window, SetTimer call, OnExit handler |
| `dt`, `ign` | `;@declare-type` and `;@ignore-next-line` pragmas |

*Code > Surround With* (*Ctrl+Alt+T*) wraps the selected lines in `If`, `While`, `Loop`, `For` or
`Try ... Catch`.

*File > New > AutoHotkey Script* creates a file with `#Requires AutoHotkey v2.0`. The same template fills every new,
empty `.ahk` file the IDE creates (e.g. *New > File* with the name `tool.ahk`); empty files from outside (Explorer,
Git) stay empty. The template can be changed under *Settings > Editor > File and Code Templates*.

## Running scripts

- *Run 'script.ahk'* in the context menu of the editor or the project view (*Ctrl+Shift+F10*) creates and
  starts a run configuration for the file. An existing configuration for the same script is reused.
- For a main script, create a configuration of type *AutoHotkey* under *Run > Edit Configurations* with the
  script, its parameters and the working directory (empty = directory of the script).

The script is started with `/ErrorStdOut`, so load errors (syntax errors found by AutoHotkey) appear in the
console with a clickable link to the file and line. Runtime errors are still shown by AutoHotkey in its own
dialog.

The program is taken from the settings; if the field is empty, the plugin looks for the launcher
`UX\AutoHotkeyUX.exe` (which selects the version according to `#Requires`) and then for `v2\AutoHotkey.exe` in
the usual installation folders. If the program is the launcher and the script requires AutoHotkey 2.0
(`#Requires AutoHotkey v2.0`, also `v2`, `2.0.18`, `>=2.0`), the plugin starts the v2 interpreter next to it
directly (`v2\AutoHotkey.exe`, or `AutoHotkey32.exe`/`AutoHotkey64.exe` for `32-bit`/`64-bit`); this also applies
to the validation. Scripts that require 2.1 or later, or have no `#Requires`, still go through the launcher.

## Debugging

The *Debug* button (*Shift+F9*) works with the same AutoHotkey run configurations as *Run*. The plugin starts the
AutoHotkey v2 interpreter with its built-in debugger (also when the settings name the launcher) and connects to it.

- **Breakpoints**: click the gutter of a line with code (not on empty or comment lines). AutoHotkey may move a
  breakpoint to the next line with code, e.g. on a line with only `}`; the breakpoint still counts as verified.
  Breakpoints can be set and removed while the script runs. A condition or log expression can be a variable or
  member (`debug`, `obj.flag`, `arr[1]`): the breakpoint suspends if the value is true (not unset, empty or 0), the
  log shows `path = value`. Other expressions are ignored (the console says so), because the engine of AutoHotkey
  cannot evaluate expressions.
- **Stepping**: *Resume*, *Pause*, *Step Over*, *Step Into*, *Step Out*, *Run to Cursor*, *Stop*.
- **Frames**: the call stack with function or thread (`Auto-execute thread`, `Add()`; calls of built-in functions
  such as `Sleep()` appear as their own frame). Clicking a frame opens the line.
- **Variables**: local variables directly, global variables in the group *Global* with built-in variables,
  classes and functions in the sub-group *Built-in*. Objects, arrays and maps can be expanded (100 children at a
  time, a link loads the next ones; `<base>` is the prototype).
- **Set Value** (*F2* on a variable or member): enter a number (`42`, `-3`, `0x1F`, `1.5`), `true`/`false`, or text
  in quotes (`"text"` or `'text'`, with AutoHotkey's escapes such as `` `n `` and `` `" ``). Unquoted text is
  rejected: in AutoHotkey it would be a variable name.
- **Watches, Evaluate, hover**: only variables and members can be evaluated (`name`, `obj.key`, `obj.a.b`,
  `arr[1]`, `m["key"]`); AutoHotkey cannot evaluate expressions such as `a + b` or calls. A name is looked up among
  the local variables of the frame, otherwise globally. Hovering a name shows its value (up to the name under the
  mouse, e.g. `obj.nested` on `nested`); built-in functions and classes, calls and definitions show none.
- **Inline values**: while suspended, the editor shows `name = value` at the end of the lines where a variable of
  the current function (or of the top-level code) is used, up to the current line (setting *Show values inline*).
  At the top level the group *Global* is expanded automatically for this.
- **Exception breakpoint**: *Run > View Breakpoints* (*Ctrl+Shift+F8*) has one entry *AutoHotkey Exceptions*,
  disabled by default. AutoHotkey knows no error classes, so it applies to all errors; the check boxes choose
  *Caught* (inside `try`) and *Uncaught* (default: uncaught only; it stops before AutoHotkey shows its error
  dialog). The console says which kind of error stopped the script; the error message itself appears when you
  continue.
- **Detach**: the toolbar button *Detach* disconnects the debugger; the script keeps running.
- **Attach to a running script**: *Run > Attach to AutoHotkey Script...* lists the running AutoHotkey v2 scripts and
  attaches the debugger to the chosen one (useful for scripts that run permanently). The script pauses when the
  debugger connects; *Stop* detaches and leaves the script running.
- **Mark Object** (*F11* on an object value) marks the object, so that other variables pointing to the same object
  show the mark.
- *Pause* on a script that waits for hotkeys or timers (no line running) stops without a call stack.
- **Output**: the console shows the output of the script and the text of `OutputDebug`.
- If the script has a syntax error, AutoHotkey does not start the debugger; the console shows the error (clickable)
  and the session ends. With the option *Compile with Ahk2Exe* the script itself is debugged, not the executable.

## Validating with AutoHotkey

The plugin's parser does not know every construct, and some errors can only be found by AutoHotkey itself (for
example a missing `#Include` file). *Code > Validate with AutoHotkey* (*Alt+Shift+V*, also in the context menu of
the editor and the project view) saves all modified AutoHotkey files and runs
`AutoHotkey /ErrorStdOut /validate script.ahk`: the script is loaded but not run.

- Which script: if an AutoHotkey run configuration is selected in the toolbar and the current file is its script
  or is included by it (also indirectly), its script (the main script) is validated (an included file alone usually
  fails, because it lacks the definitions of the main script). Otherwise the current file is validated, also in a
  project that collects many small scripts whose run configurations have nothing to do with the current file.
- AutoHotkey checks a temporary copy next to the script (`name.~validate.ahk`, deleted afterwards) with the line
  `#Warn All, StdOut` appended. It overrides every other `#Warn` mode of the script, so warnings never open a
  message box; the line numbers stay the same, and the output names the original file.
- The console shows which script is validated, the output of AutoHotkey and a summary (the command line is not
  shown). Errors have a clickable link to the file and line; warnings are listed but do not count as errors.
  The summary is green if AutoHotkey found no errors and red if it reports an error (or could not finish);
  the number of warnings and of accepted parser errors is shown in normal text. *Reformat Code* on an
  AutoHotkey file empties the console, so no outdated output stays until the next validation.
- The line of the reported error is marked in the editor ("AutoHotkey: ..."), also in an included file, until
  the file is changed.
- If AutoHotkey accepts the file but the plugin's parser reports syntax errors, these are shown as
  "Syntax error (AutoHotkey accepts this code)", and the console says how many there are. The parser does not
  understand these lines; reformatting them may change the code.
- AutoHotkey reports only the first error. Runtime errors are not found.
- If AutoHotkey still waits for a dialog, the plugin stops it after 15 seconds and says so in the console.

Under *Settings > Languages & Frameworks > AutoHotkey*, *Validate with AutoHotkey* can also start the
validation automatically, either after *Reformat Code* or whenever an AutoHotkey file is saved (note that the
IDE also saves automatically, e.g. when it loses focus). Automatic runs open the console only if AutoHotkey
reports an error. Here, too, the same rule applies (saving an included file validates the main script).

## Compiling with Ahk2Exe

*Code > Compile with Ahk2Exe* (also in the context menu of the editor and the project view) turns a script into
an executable with Ahk2Exe, the compiler of the AutoHotkey installation (`Compiler\Ahk2Exe.exe`).

- Like the validation, it compiles the script of the selected AutoHotkey run configuration if the current file
  belongs to it, otherwise the current file. Modified AutoHotkey files are saved first.
- Only prepared scripts are compiled: the script must choose its base file (the AutoHotkey interpreter embedded
  in the executable) with a line `;@Ahk2Exe-Base`, for example

  ```autohotkey
  #Requires AutoHotkey v2.0
  ;@Ahk2Exe-Base ..\v2\AutoHotkey64.exe
  ;@Ahk2Exe-SetProductVersion 1.0.0
  ;@Ahk2Exe-SetMainIcon app.ico
  ```

  A relative base path is relative to the folder of Ahk2Exe. Without this line the plugin does not compile:
  Ahk2Exe would otherwise use the base file chosen last in its own window, which may be one of AutoHotkey v1.
  A notification then offers *Add Ahk2Exe directives*; the same is available with *Alt+Enter* (*Add Ahk2Exe
  compiler directives*) in every script without the line. It inserts a head below `#Requires` with the base file
  (`AutoHotkey64.exe`, or `AutoHotkey32.exe` for `#Requires ... 32-bit`), product name, version, description,
  language and copyright (placeholders, visited with *Tab*) and disabled lines (`;;@`) for icon and console
  application. Name, version and description are script variables that the directives read via `A_PriorLine`,
  so the script can use them too.
- Everything else (name of the executable, icon, version information ...) comes from the `;@Ahk2Exe-...`
  directives of the script; by default the executable is created next to the script.
- The plugin runs `Ahk2Exe.exe /in script /compress n /silent verbose` in the background. The console shows the
  output of Ahk2Exe and a summary; syntax errors are clickable.

The settings page has the path to Ahk2Exe (empty = automatic: `Compiler\Ahk2Exe.exe` of the installation) and
the compression (*None*, the default, or *UPX*, which needs `upx.exe` next to Ahk2Exe).

### Compile and run

A run configuration can compile the script first and run the executable instead of the script: option
*Compile with Ahk2Exe and run the executable*. The executable gets the script parameters and the working
directory of the configuration. This is meant for console applications (`;@Ahk2Exe-ConsoleApp`), which need a
real console. *Run the executable in* chooses where:

- *Terminal in the Run tool window*: a pseudo console inside the IDE. ANSI escape sequences (colors, cursor
  movement), input and console functions work. The output of Ahk2Exe is shown above the output of the program.
- *Separate console window*: a normal Windows console window, as when the executable is started from Explorer.
  The Run tool window shows the output of Ahk2Exe and, when the program ends, its exit code.

If compiling fails, the console shows the errors of Ahk2Exe (clickable) and the executable is not started. Note
that AutoHotkey v2 sets `A_WorkingDir` to the script folder at startup; the working directory of the run
configuration is available as `A_InitialWorkingDir`.

## Settings overview

| Where | What |
|-------|------|
| *Languages & Frameworks > AutoHotkey* | path to AutoHotkey (empty = automatic), command line of the external help, automatic validation, Ahk2Exe path and compression |
| *Editor > Color Scheme > AutoHotkey* | colors |
| *Editor > Code Style > AutoHotkey* | indent, spaces, tab *Case* |
| *Editor > Live Templates > AutoHotkey* | templates |
| *Editor > File and Code Templates* | template *AutoHotkey Script* |

## Known limitations

- Only the file itself and its directly included files are searched (*Find Usages* searches the reverse direction:
  the files that include the file of the definition, *Rename* also indirectly); there is no project-wide search.
  *Rename* works on names, not types, so its preview has to be checked (see "Rename"). Tip: with names that are
  unique in the project (methods, properties, functions, classes) every occurrence in the preview belongs to the
  renamed definition; for variables use *Replace in Files* (*Ctrl+Shift+R*) with *Words* on and *Match case* off
  (AutoHotkey ignores case), and check its preview for hits in strings and comments.
- Variables are not resolved; navigation and completion work with names only.
- Inside parentheses, a following line that starts with a fat arrow function (`() => 1`) is not recognized as a
  concatenation. (A following line starting with `!`, `not`, `~`, `++` or `{...}` is an error in AutoHotkey too.)
- The command form of a method call (`obj.Method "x"`) is read as a concatenation.
- Calls without parentheses whose first argument starts with `(` (`SetTimer () => ...`) are not supported;
  use `;@ignore-next-line`.
