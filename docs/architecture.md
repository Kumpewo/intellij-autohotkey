# Architecture

This document explains how the parts of the plugin work together. All classes are in
`src/main/java/de/kumpewo/autohotkey/`; every class has a Javadoc comment describing its purpose. All
extensions are registered in `src/main/resources/META-INF/plugin.xml`.

## Overview

```
                 Ahk.flex ──JFlex──> _AhkLexer ──> AhkLexerAdapter
                                                       │ tokens
                 Ahk.bnf ──Grammar-Kit──> AhkParser <──┘ (+ AhkParserUtil)
                                              │ PSI tree (AhkFile, AhkTypes, psi/*)
        ┌──────────────┬──────────────┬───────┴───────┬──────────────┬──────────────┐
   annotation/    completion/    navigation/      formatter/    documentation/   structure/
   (colors,       (completion,   include/         (indent,      (quick doc,      (structure view,
    errors)        parameter      (Ctrl+B)         spacing,      external help)   breadcrumbs)
        │          info)              │            case)              │
        └────────┬───────┴────────────┴──────┬───────┘                │
           highlighting/AhkNames        types/AhkTypeDeclarations      │
           (name lists in names/*.txt)  (;@declare-type pragmas)       help/ pages
```

Shared knowledge is concentrated in a few places so that highlighting, completion, formatting and help agree:

- **Roles of names** are decided in `AhkAnnotator` (`isName`, `keywordColor`, `identifierColor`). The case
  adjustment of the formatter uses the same functions.
- **Built-in names** come from the lists in `src/main/resources/names/`, loaded by `AhkNames`.
- **Included files** are resolved by `AhkInclude.includedFiles`; navigation, completion, parameter info and type
  declarations all look at the file itself and its *directly* included files.
- **Declared types** come from `AhkTypeDeclarations` (also for parameter info and help variants).
- **Visible texts** are in `messages/AhkBundle.properties` and are read with `AhkBundle.message(...)`.

## Language core

| Class | Role |
|-------|------|
| `AhkLanguage`, `AhkFileType`, `AhkFile`, `AhkIcons` | language, file type `.ahk`, PSI file, icon |
| `src/main/grammar/Ahk.flex` | lexer: strings, comments, hotkeys, hotstrings, continuation lines, keywords |
| `parser/AhkLexerAdapter` | wraps the generated JFlex lexer |
| `src/main/grammar/Ahk.bnf` | grammar: statements, expressions (Pratt), classes, switch, directives |
| `parser/AhkParserUtil` | external predicates and rules used by the grammar |
| `parser/AhkVersion` | evaluates `#Requires AutoHotkey ...` |
| `parser/AhkParserDefinition` | connects lexer, parser and PSI |
| `psi/AhkTypes` (generated), `psi/AhkElementType`, `psi/AhkTokenType` | element and token types |
| `psi/*Mixin`, `psi/AhkDefinitions`, `psi/AhkNamedElement` | hand-written behavior of PSI elements |

### Line ends

The most important design decision: the end of a line ends a statement. The lexer therefore returns `CRLF` as
a real token, and the grammar allows `CRLF*` only where AutoHotkey allows line breaks (inside brackets, after
a comma, between head and body). The only whitespace token is `WHITE_SPACE`.

Line continuations are handled in the lexer with states (`IN_CONTINUATION`, `IN_SKIP_LINES` ...): after an
operator at the end of a line, and before a line that starts with an operator, the line break becomes
whitespace and the parser never sees it. Using states instead of fields lets the editor restart the lexer at
any position.

### Expressions and statements

Expressions form a Pratt hierarchy (`expr` in `Ahk.bnf`); each operator is its own PSI element (`AddExpr`,
`MemberExpr`, `ConcatExpr` ...). Implicit concatenation (`a b`) is an operator without a token; its rule checks
the whole operand by lookahead first, because a token-less operator whose operand fails would make the Pratt
loop spin forever.

There is no symbol table: `f (x)` is always a concatenation and `f(x)` a call; a command without parentheses
(`MsgBox "x"`) is recognized by the token after the name.

Control structures have a head expression and a `BODY` element (block or a single statement on the next line);
`else` is its own element `ELSE_BRANCH`. The formatter relies on `BODY` for indentation.

### Error recovery

`recoverWhile` limits an error to the rest of the line. A few cases are parsed normally and only marked as
an error by an external rule in `AhkParserUtil`, so that the rest of the file stays intact:
`strayInSwitch` (statement before the first `case`) and `strayBrace` (surplus `}` at file level). Files for
another AutoHotkey version are swallowed as one `OTHER_VERSION_CODE` element (`otherVersion`).

### PSI mixins

The generated PSI classes extend hand-written mixins (`mixin=` in the grammar):

- `AhkFuncMixin`, `AhkClassMixin`, `AhkClassPropertyMixin`, `AhkLabelMixin`: name, navigation target on the name
  (`getTextOffset`), presentation in the structure view and in choosers.
- `AhkReferenceHostMixin`: lets `ref_expr`/`literal_expr` ask the registered reference contributors.
  `ASTWrapperPsiElement` does not do that by itself.
- `AhkElseBranchMixin`: the text offset of an `else` branch is the keyword, not the preceding line break.

## Highlighting and inspections (`highlighting/`, `annotation/`)

- `AhkSyntaxHighlighter` colors only what the lexer knows for sure: strings, numbers, comments, hotkeys and
  hotstrings. Keys are defined in `AhkColors`, defaults in `colorSchemes/AhkDefault.xml`, the settings page is
  `AhkColorSettingsPage`.
- `AhkAnnotator` does the semantic coloring on the PSI tree: keywords by token type, directives, and identifiers
  by role (free-standing name, name after the dot, nested class `Gui.Button`, `default` in a switch, loop
  subcommands, declared types).
- `AhkNames` loads the name lists (`directives`, `variables`, `classes`, `functions`, `methods`, `properties`,
  `nested-classes`, `keys`, `members`). The tables live in holder classes because extension classes must not
  use static initialization and annotators run in parallel.
- Further annotators each check one rule: `AhkBlockBraceAnnotator` (nothing after `{`), `AhkElseBodyAnnotator`,
  `AhkBadCommentAnnotator`, `AhkDeclAnnotator` (`global` without a name), `AhkVersionAnnotator` with the quick fix
  `AhkAddRequiresFix`, `AhkTypeDeclarationAnnotator`.
- `AhkVersionDetector`: for a file without `#Requires` it runs the syntax detection of the AutoHotkey launcher
  (`IdentifyBySyntax` from `UX/inc/identify.ahk` of the installation) through a small helper script with
  `AutoHotkeyUX.exe`, in the background, once per text and debounced; `AhkVersionAnnotator` then shows a warning with
  the fix for `#Requires AutoHotkey v1.1` first if the code looks like v1. The launcher's own `/Which` is not used,
  because it shows an error dialog when the version is unclear.
- `;@ignore-next-line`: `AhkIgnoredLines` finds the marked lines (cached per file), `AhkIgnoredLineErrorFilter`
  hides their parser errors, `AhkIgnoredLineAnnotator` colors them and warns at the marker.
- Generic syntax errors: the technical messages generated by Grammar-Kit ("<expr> expected, got 'x'") are hidden
  by `AhkSyntaxErrorFilter` and shown again as "Syntax error" by `AhkSyntaxErrorAnnotator` (on the file, so that
  an empty error element can be marked on the next character). The plugin's own parser messages from
  `AhkParserUtil` (`'case' or 'default' expected`, `Unexpected '}'`) stay; a new own message must be added to
  `AhkSyntaxErrorAnnotator.OwnMessages`.

## Type declarations (`types/`)

`AhkTypeDeclarations` parses the comment pragmas `;@declare-type`, `;@declare-auto-type` and `;@declare-private` of
a file and of the files named `pragmas.ahk` that it includes directly (cached until the PSI changes, because own
classes and pragmas can come from included files). It answers:

- `lookup(file, name)`: declared classes of a name and whether the declaration is explicit (no wildcard);
- `privacy(file, name)`: whether a member name is declared private (exactly or through a wildcard); `AhkAnnotator`
  uses it for member definitions, fields and names after the dot (color `AHK_PRIVATE_MEMBER`);
- `hierarchy(file, className)`: the class and its base classes as `Level` objects (own `AhkClass` or a built-in
  class from `members.txt`);
- `members(file, declared)`: instance members along the hierarchy with their depth (for completion order);
- `problems(comment)`: warnings for the annotator (in a `pragmas.ahk` without "Unknown class.").

`annotation/AhkInspectionSuppressor` suppresses the platform inspection "Non-ASCII characters" for names in
AutoHotkey files (AutoHotkey allows non-ASCII characters in names, e.g. a `§` prefix for private members).

## Completion and parameter info (`completion/`)

- `AhkCompletionContributor` decides by the leaf at the caret: directive, string (parameter values, key names),
  directive arguments (values; for `#Include` file paths via `AhkIncludeCompletion`, which uses
  `AhkInclude.includeDirectory`/`typedDirectory`/`libraryDirectories`), hotkey (key names), name after the dot (members), free-standing name (built-ins, own
  definitions, keywords). It uses `AhkNames`, `AhkTypeDeclarations`, `AhkInclude` and `AhkKeywordCase` for the
  keyword spelling.
- `AhkArguments` finds the owner of the arguments at a position (call, command, directive, method call) and the
  index of the current argument.
- `AhkCommandInfo` loads `signatures.txt`, `method-signatures.txt`, `values.txt` and `options.txt` (option strings,
  with groups `@Name`; `options(...)` with the rule `Class.Method<FirstArgument>`) and builds signatures of own
  functions and methods from their parameter lists.
- `AhkParameterInfoHandler` shows the forms for the owner found by `AhkArguments`.
- `AhkTypedHandler` opens the popup after `.` and `#` (in an include line also after `<`, quotes, `\`, `/`), the parameter info after `(`, and types `%` in pairs;
  `AhkQuoteHandler` types quotes in pairs.

## Navigation (`navigation/`, `include/`)

- `AhkDefinitionReferenceContributor` attaches an `AhkDefinitionReference` to free-standing names, command
  names, names after the dot and the names after `extends`. The reference is poly-variant: it collects matching
  functions, classes and properties in the file and its directly included files. Without a definition but with a
  help page, the target is an `AhkHelpTarget`, which opens the quick documentation.
- `AhkLabelReferenceContributor` / `AhkLabelReference`: targets of `goto`, `break`, `continue`.
- `AhkInclude` resolves `#Include` arguments (library folders, built-in variables, current include directory);
  `AhkIncludeReferenceContributor` / `AhkIncludeReference` open the file, `AhkIncludeDirectoryTarget` shows a hint
  for a directory.
- `AhkLibraryRootsProvider` registers the global Lib folders as a synthetic library, because the platform runs no
  annotators on files outside of the project and its libraries.
- `AhkReference` / `AhkReferenceContributor` are an early draft and are not registered.
- `AhkFindUsagesProvider` (`lang.findUsagesProvider`) enables Find Usages for `AhkNamedElement`s and
  `AhkFieldTarget` (which reports the AutoHotkey language for this); `AhkReferencesSearcher` (`referencesSearch`)
  collects the references: in the file of the definition and the AutoHotkey files of the scope that include it
  directly (`AhkInclude.includedFiles`), every reference whose targets contain the definition. No words scanner and
  no word index (it is case-sensitive).
- Go to Symbol / Go to Class: `AhkSymbols.collect` lists the definitions of a file (functions, methods, classes,
  properties, fields, labels, hotkeys, hotstrings) with name, qualified name and display text. `AhkSymbolIndex`
  (`fileBasedIndex`, keys only) maps the names to the files; validation copies are not indexed. Increase its version
  when `collect` changes. `AhkGotoSymbolContributor` (`gotoSymbolContributor`) and `AhkGotoClassContributor`
  (`gotoClassContributor`, classes only) read the names from the index and collect the elements from the files
  found; they implement `GotoClassContributor` for qualified names (`Outer.Inner.Method`). Entries are
  `AhkSymbolItem`s (a `PsiElementNavigationItem` that shows the file as location, unlike the elements' own
  presentation used by the Ctrl+B choosers). The scope (project, libraries with "Include non-project items") comes
  from the platform.
- Rename (Z.20): `AhkRenameProcessor` (`renamePsiElementProcessor`) forces the preview, finds code occurrences with
  `AhkReferencesSearcher.search(..., transitive = true)` (also indirectly including files; references there are
  checked with `AhkDefinitionReference.definitionsIn` over the file's whole include closure), adds occurrences in
  comments and strings as `NonCodeUsageInfo` in `findCollisions` (the platform's own search needs the word index)
  and reports conflicts. `setName` of the definitions and `handleElementRename` of the references replace the text
  of one leaf (`psi/AhkRenames`). `AhkNamesValidator` (`lang.namesValidator`), `AhkRefactoringSupportProvider`
  (no inline rename) and `AhkRenameVeto` (help and directory targets) complete it. The searcher enumerates
  AutoHotkey files by walking the content roots and additional libraries, not through the file type index (in tests
  the index queried from the background thread did not show files of a content root added in the same run).
- `AhkHighlightUsagesHandlerFactory` (`highlightUsagesHandlerFactory`): occurrences of the name under the caret
  (automatic identifier highlighting and Ctrl+Shift+F7). Name-based like navigation: every `IDENTIFIER` with the
  same name, ignoring case; definitions and assignment targets are write occurrences. `highlightReferences()` is
  false, so the platform does not add its own reference search.

## Help (`documentation/`)

- `AhkHelpRepository` knows which identifiers have help: the lookup files `help/lookup/docs.txt` (help shipped
  with AutoHotkey) and `help/lookup/extra.txt` (own pages), each line `word<TAB>path<TAB>anchor[<TAB>class]`
  (path relative to `help/`, anchor = `id` of a direct child of `<body>` or `<null>`). `docs.txt` is searched
  first. It loads pages as resources (path in lower case), cuts the page between `<body>` and the anchor,
  replaces stylesheet links by an inline style block (CSS from `help/styles/`, a copy of the styles of the
  AutoHotkey help, so that `help/docs/` can be replaced by a new version of the help at any time) and rewrites links between
  pages to `psi_element://`.
- `AhkDocumentationProvider` decides the help key by role (`helpKey`, e.g. `Gui.Button`; in a pragma comment
  the marker without `;`, e.g. `@declare-type`, because a line starting with `;` is a comment in the lookup
  files; `documentationElement` also takes the comment when the caret is at its end), narrows several
  entries of a member with their class and the class left of the dot, and implements the quick documentation and
  the external help hooks (key `path` or `path#anchor`; entries from `extra.txt` only if `help/extra.chm`
  exists).
- `AhkExternalHelp` builds and starts the command line of the external viewer (`docs.txt`: setting or
  `AutoHotkey.chm`; `extra.txt`: always `hh.exe` with `help/extra.chm`, copied by `extraChm` to
  `<temp>/intellij-autohotkey/extra-<crc32>.chm` because `hh.exe` cannot read from the JAR; the CHM is built from
  `help/extra.hhp`) and shows the chooser for several
  entries.
- `AhkHelpElement` is the target of a link inside a page.
- `AhkManual` + `AhkManualAction` (menu *Help > AutoHotkey Plugin Manual*, also a link on the settings page) open
  the user manual (`help/extra/index.htm`): with `help/extra.chm` in `hh.exe` (`AhkExternalHelp.openExtra`), otherwise
  the files listed in the `[FILES]` section of `help/extra.hhp` are copied to `<temp>/intellij-autohotkey/manual/` and the
  start page opens in the browser. `extra.hhp` is the single list of the manual's files (pages and `help/styles/`);
  `testManualFilesAndLinks` checks that every page is listed and every link and anchor between them exists.
- `AhkHelpAction` (menu *Help > AutoHotkey Help*) calls `AhkExternalHelp.openDocs`: the command line of the
  settings with the key `/docs/index.htm`, otherwise `hh.exe AutoHotkey.chm`.
- In a compiler directive `;@Ahk2Exe-...` the help key is `@Ahk2Exe` (`AhkDocumentationProvider.pragmaKey`), an
  entry of `docs.txt` that points to `/docs/misc/Ahk2ExeDirectives.htm`.
- Doc comments (Z.22): `AhkDocComment.of(definition)` finds the `/** */` before a function, method or class (the
  lexer splits a block comment into several `BLOCK_COMMENT` tokens; they are joined) and parses description and
  tags, with absolute ranges of `@param` names and types for warnings; cached until the next PSI change.
  `AhkOwnDoc` renders the page (`DocumentationMarkup`, Markdown via the platform's `DocMarkdownToHtmlConverter`,
  signature from `AhkCommandInfo.ownSignature`), finds the definitions of the token under the caret through the
  `AhkDefinitionReference` (`definitions`; own definitions go before the help topics in
  `AhkDocumentationProvider.generateDoc`), builds the overview for several matches and the links
  `psi_element://ahkdef:<offset>:<url>` (`link`/`resolveLink`) and resolves `@see` targets. Completion items of
  own functions, methods and classes carry the definition as lookup object
  (`getDocumentationElementForLookupItem`); `AhkCommandInfo.Parameter.description` feeds the parameter info.
  `AhkTypeDeclarations.lookup(PsiElement, name)` adds the types of `@param {Type}` of the enclosing function to the
  pragmas. `annotation/AhkDocCommentAnnotator` warns about unknown parameters and classes (on the file, because the
  ranges lie outside of the definitions) and strikes out uses of deprecated definitions; `template/AhkDocEnterHandler`
  (`enterHandlerDelegate`) writes the skeleton after `/**` + Enter.

The help under `src/main/resources/help/` is part of the repository: `docs/` is a copy of the AutoHotkey
documentation (currently v2.0.31) and can be replaced by a newer one at any time, `styles/` holds the stylesheets
the plugin uses (copied from `docs/static/`), `extra/` the plugin's own pages (manual, pragmas, images),
`lookup/` the lookup files, and `extra.hhp`/`.hhc`/`.hhk` the HTML Help project for `extra.chm` (compiled
with `hhc.exe extra.hhp`; the compiled `extra.chm` is checked in as well).

## Formatter (`formatter/`)

- `AhkFormattingModelBuilder` creates the block tree and the spacing rules from the code style options. Spacing
  rules never change line breaks.
- `AhkFormattingModel` (a `PsiBasedFormattingModel`) handles formatting through the PSI
  (`CodeStyleManager.reformat(PsiElement)`: previews of the code style page, refactorings). A line break is a real
  token (`CRLF`, one token per run of line breaks) but no formatter block, so the whitespace between two blocks can
  contain `CRLF` tokens. `replaceWithPsiInLeaf` splits the new whitespace like the lexer (runs of line breaks and of
  other whitespace) and puts it into the existing `CRLF` and whitespace tokens (reusing, adding, removing), so the
  tree equals a fresh parse. The platform's own replacement only knew `WHITE_SPACE` tokens and doubled the line
  breaks. "Reformat Code" works on the document and does not use this. The platform does not reduce surplus blank
  lines through the PSI (only "Reformat Code" does). `assertFormatted` in the tests checks both paths.
- `AhkBlock` computes indents (content of brackets, bodies without a block, `case` bodies, continuation lines)
  and the indent after Enter (`getChildAttributes`, `isIncomplete`).
- `AhkLanguageCodeStyleSettingsProvider` selects the standard options and the preview, `AhkCodeStyleSettings`
  holds the own options, `AhkCodeStyleSettingsProvider` builds the page and adds the tab *Case*
  (`AhkCaseCodeStylePanel`).
- Case adjustment runs after the formatter in `AhkCasePostFormatProcessor`: it replaces leaf texts whose role
  (from `AhkAnnotator`) has a known spelling (`AhkNames`, `AhkKeywordCase` with `names/keyword-case.txt`,
  modes in `AhkCaseMode`).
- `AhkDocCommentPostFormatProcessor` (always on) aligns the lines of doc comments that start with `*` under the
  `/**` line, which the formatter moves as one leaf; it replaces the text tokens of the comment inside
  `PostprocessReformattingAspect.disablePostprocessFormattingInside` (otherwise the platform shifts the lines of a
  replaced multi-line token once more). The lexer keeps the indentation before `/*` as its own whitespace token.
  With the main switch `DOC_FORMAT` (tab *Doc Comments*, `AhkDocCommentCodeStylePanel`, switches `DOC_*` in
  `AhkCodeStyleSettings`, read and written by name) it rewrites the whole comment with `AhkDocCommentFormatter`
  (a pure text function: parse into description and tag blocks, adjust `@param`, render with alignment, blank lines
  and Markdown-aware wrapping) and replaces its tokens by one; the parameters come from
  `AhkDocComment.documentedDefinition`.
- `AhkCommenter` defines the comment characters.

## Spell checking (`spellchecker/`)

Registered in `META-INF/ahk-spellchecker.xml`, an optional dependency on `com.intellij.modules.spellchecker`.
`AhkSpellcheckingStrategy` (`spellchecker.support`) passes comments (not `;@` pragma comments), strings without
quotes and escape sequences, and `HOTSTRING_TEXT` to the spell checker; names in the code are not checked (a
"change to" fix would change one occurrence only; names are changed with Rename). `AhkDictionaryProvider`
(`spellchecker.dictionary.runtimeDictionaryProvider`) supplies the dictionary *AutoHotkey*: all names of the name
lists and their parts. The inspection itself belongs to the bundled Grazie plugin, which the tests don't load; they
check the tokenizers and the dictionary directly.

## Structure (`structure/`)

`AhkStructureViewFactory` → `AhkStructureViewModel` → `AhkStructureViewElement` (hotkeys/hotstrings, classes,
functions, labels; skips blocks), sorted by `AhkStructureSorter`. `AhkBreadcrumbsProvider` provides breadcrumbs
and, through the platform, sticky lines. `AhkFoldingBuilder` (`lang.foldingBuilder`, dumb-aware, syntax tree
only) folds brace pairs, block comments (the lexer returns them in several `BLOCK_COMMENT` tokens), continuation
sections, runs of comment and `#Include` lines and `;region`/`;endregion` and `;{`/`;}` regions. The platform's
custom folding providers only know `//`, `#` and `/*` as comment prefixes, so the regions are recognized here.

## Run and settings (`run/`, `settings/`)

- `AhkSettings` is an application-level light service (`@Service`, stored in `autohotkey.xml`): AutoHotkey path,
  external help command, trigger of the automatic validation, Ahk2Exe path and compression, automatic detection
  of the installation, the launcher and Ahk2Exe (`detectAhk2Exe`). `findAutoHotkey(script)` replaces the launcher
  by the v2 interpreter next to it (`v2Interpreter`) if the script requires AutoHotkey 2.0
  (`AhkVersion.scriptRequirement`, read from the saved file with `AhkCompiler.readDirectives`); used by running and
  validating.
- `AhkSettingsConfigurable` is the settings page; changing the path updates the library roots.
- `AhkRunConfigurationType` (with its factory), `AhkRunConfiguration`, `AhkRunConfigurationOptions` and
  `AhkRunConfigurationEditor` form the run configuration; `AhkRunConfigurationProducer` creates it from the
  context; `AhkRunState` builds the command line and starts the process; `AhkErrorFilter` links load errors in
  the console.

## Validation with AutoHotkey (`validate/`)

- `AhkValidator.target` picks the script: the one of the selected `AhkRunConfiguration` (`RunManager`), otherwise
  the file itself. `AhkValidator` saves all modified AutoHotkey documents, writes a copy next to the script
  (`copyPath`) with `#Warn All, StdOut` appended (`withWarnToStdOut`, keeps encoding and line separator) and runs
  `AutoHotkey [launcher.ahk] /ErrorStdOut /validate copy` (command line from `AhkRunState.createCommandLine`) as a
  background task with a timeout (in case a dialog blocks it). `restorePaths` maps the copy back to the original
  in the output. It parses the first `path (line) : ==> message` that is not a warning, stores the result (also
  "accepted" for the file the validation was started from, if the script includes it, directly or indirectly)
  and shows the output in a console with `AhkErrorFilter` (manual runs always, automatic runs only on an error).
- `AhkValidation` (project light service) keeps the result per file together with the document's modification
  stamp; a result is ignored as soon as the document changes. `AhkValidationAnnotator` marks the error line,
  `AhkSyntaxErrorAnnotator` adds "(AutoHotkey accepts this code)" to the parser's syntax errors.
- `AhkValidateAction` is the menu action; `AhkValidateTriggers` (application listener for
  `FileDocumentManagerListener` and `AnActionListener`) starts the validation after saving or after
  *Reformat Code*, according to `AhkSettings.ValidateTrigger`.

## Compilation with Ahk2Exe (`compile/`)

- `AhkCompileAction` is the menu action; `AhkCompiler.compile` uses the same target as the validation
  (`AhkValidator.target`), saves the AutoHotkey documents (`AhkValidator.saveAhkDocuments`) and requires a line
  `;@Ahk2Exe-Base` in the script (`hasBaseDirective`); otherwise a balloon notification (group `AutoHotkey`)
  offers "Add Ahk2Exe directives"; without Ahk2Exe it shows a hint in the editor (a dialog without an editor).
- `AhkAhk2ExeHeader` builds the head of compiler directives as live template (placeholders for name, version,
  description, copyright; base `AutoHotkey32/64.exe` after `#Requires`) and inserts it below the first `#Requires`
  line; used by the notification and by the intention `AhkAddAhk2ExeHeaderIntention` (Alt+Enter in files without
  base directive, no warning; `startInWriteAction` is true, otherwise the platform keeps the document read-only
  while the intention runs; description under `intentionDescriptions/`).
- It runs `Ahk2Exe /in script /compress n /silent verbose` (`createCommandLine`, working directory = folder of the
  script, ANSI code page) as a cancellable background task with a timeout and shows stdout/stderr plus a summary in
  a console with `AhkErrorFilter` (syntax errors have the `/ErrorStdOut` format). `compiledFile` reads the path of
  the executable from the output ("Successfully compiled as: ..."); the plugin refreshes it in the VFS.
- Run configuration option "compile first" (`AhkRunConfiguration.isCompileFirst`, `ExeConsole`): `AhkRunState`
  overrides `execute`. It runs `AhkCompiler.run` (modal progress on the EDT) and, if that fails, returns a console
  with the Ahk2Exe output and a `NopProcessHandler` that terminates at once. Otherwise it starts the executable
  (`exeCommandLine`): `TERMINAL` = `PtyCommandLine` + `KillableProcessHandler` (`setHasPty`) +
  `TerminalExecutionConsole` (the Ahk2Exe output is printed first, with CR LF); `WINDOW` = `powershell.exe
  -EncodedCommand` with `Start-Process -Wait -PassThru; exit $p.ExitCode` (`startProcessScript`), because
  `cmd /c start /wait` does not return the exit code. `checkConfiguration` requires `;@Ahk2Exe-Base` and Ahk2Exe
  instead of the AutoHotkey program.

## Debugger (`debug/`, in progress)

The debugger talks DBGp with the engine built into AutoHotkey v2 (`AutoHotkey64.exe /Debug=127.0.0.1:port`). It
follows the DBGp specification; the observed behavior of the engine is covered by the tests (`DbgpTest`,
`AhkDebugTest`).

- `AhkDebugRunner` (`programRunner`, debug executor, AutoHotkey run configurations) opens a `DbgpServer`, starts the
  v2 interpreter (`AhkSettings.findInterpreter`, never the launcher) with `/ErrorStdOut /Debug=…` and starts the
  session with an `AhkDebugProcess`.
- `AhkDebugProcess` (`XDebugProcess`) is a thin adapter: console with `AhkErrorFilter`, waits in the background for
  the engine to connect (`sessionInitialized`; a process that ends, e.g. by a syntax error, ends the session),
  passes commands and breakpoints to the controller and turns its callbacks into `breakpointReached`,
  `positionReached`, `setBreakpointVerified/Invalid` and console output (`OutputDebug`).
- `AhkDebugController` holds the logic and is tested against a fake engine: start sequence (features,
  `stderr -c 1`, breakpoints, then `run`), breakpoint table (the engine moves breakpoints on lines without code and
  shares ids; `breakpoint_get` gives the real line), hit detection by file and line of the top frame, run to cursor
  with a temporary breakpoint, an epoch per stop so that late answers are refused, and chained value requests
  (`max_depth` 0 for `context_get`, 1 for expanding).
- `AhkLineBreakpointType` allows breakpoints only on lines with code (PSI). `AhkSuspendContext`, `AhkStackFrame`
  (first context directly, other contexts as groups, built-in names in a "Built-in" sub-group), `AhkValue` (lazy
  children page by page with `tooManyChildren`, total for Array/Map from `Length`/`Count`; "Set Value"; full value
  of shortened strings; watch expression = `fullname`), `AhkDebuggerEditorsProvider` (AutoHotkey file type).
- Evaluation: `AhkNamePath` checks and normalizes name paths (the only expressions the engine can evaluate),
  `AhkDebuggerEvaluator` (per frame) resolves them with `AhkDebugController.resolve` (context of the first name from
  the frame's local variables, otherwise global) and computes the hover range from the PSI (`pathRange`).
  `AhkValueText` converts typed text for `property_set` (numbers, hex as decimal, `true`/`false`, quoted strings
  with backtick escapes), the editor text of a value and the truth of a value. Breakpoint conditions and log
  expressions that are name paths are evaluated by `AhkDebugProcess` before `breakpointReached` (the platform does
  not evaluate them).

- Comfort: `AhkInlineValues` finds the lines of a variable in the scope of the current line (innermost function,
  or top level outside functions and classes) for `AhkValue.computeInlineDebuggerData`; the *Global* group is
  auto-expanded in a frame without locals. `AhkExceptionBreakpointType` (one default breakpoint, disabled,
  properties caught/uncaught) is sent as `breakpoint_set -t exception`; the controller marks stops with the
  reasons `exception`/`error` and `AhkDebugProcess` continues at a kind that is not selected. *Detach* is a
  toolbar action (`registerAdditionalActions`), "Mark Object" a `XValueMarkerProvider` on the object address.
- Attach: `AhkAttach` finds the main windows of running v2 scripts (JNA `User32`, class `AutoHotkey`) and posts
  `AHK_ATTACH_DEBUGGER` with IP and port; `AhkAttachAction` (Run menu) starts the session with an
  `AhkAttachedProcessHandler` (no process of its own, Stop detaches).

The protocol layer (`debug/dbgp/`, plain Java apart from JDOM and the platform's executor and logger):

- `DbgpServer` listens on 127.0.0.1 with a free port; `accept` waits for the engine and stops early when the
  process has ended (a script with a syntax error never connects).
- `DbgpConnection` owns the socket: a reader thread splits the stream into packets (`DbgpFramer`: `length NUL
  xml NUL`, split and merged reads), parses them (`DbgpPacket`: `Init`, `Response`, `Stream`, `Notify`, `Unknown`)
  and completes the future of the command with the same transaction id. `send` can be called at any time, also
  while `run` waits for the next break. Streams and the end of the session go to a `Listener`; malformed packets
  are logged and skipped. All traffic is logged at TRACE level.
- `DbgpCommand` builds `name -i id -x value … -- base64`; values with whitespace are quoted (backslash and quote
  escaped). `DbgpProperty`, `DbgpStackFrame`, `DbgpBreakpoint` read the elements of responses; `DbgpUris` converts
  between Windows paths and the percent-encoded `file:///C%3A/…` URIs of the engine.

## Templates (`template/`)

`AhkTemplateContextType` defines the live template contexts (whole file, code, comment);
`liveTemplates/AutoHotkey.xml` holds the templates. `AhkCreateFileAction` is *New > AutoHotkey Script* with the
template `fileTemplates/internal/AutoHotkey Script.ahk.ft`.

## Resources

| Path | Content | Maintained |
|------|---------|------------|
| `names/directives.txt`, `variables.txt`, `classes.txt`, `functions.txt`, `methods.txt`, `properties.txt`, `nested-classes.txt`, `keys.txt` | name lists | by hand |
| `names/members.txt` | built-in class, base class, own members | generated from the help (maintainer tool) |
| `names/signatures.txt` | signatures of functions and directives | generated once from the help, corrected by hand |
| `names/method-signatures.txt` | signatures of methods | generated from the help (maintainer tool) |
| `names/values.txt` | parameter values | by hand |
| `names/options.txt` | options in option strings (MsgBox, Gui, Gui.Add ...) | by hand |
| `names/keyword-case.txt` | keyword groups and spellings for case adjustment | by hand |
| `messages/AhkBundle.properties` | all visible texts | by hand |
| `colorSchemes/AhkDefault.xml` | default colors | by hand |

The list format is simple: names separated by line breaks, spaces or commas, `;` starts a comment, case does not
matter. A new name in a list needs no code change.

## Tools (`tools/`)

Maintainer tools; they are not part of the published repository (`export-ignore` in `.gitattributes`). Python
scripts that read the AutoHotkey help under `src/main/resources/help/docs/` (class tree `objlist.htm`, linked
pages such as `lib/Array.htm`; the case of file names is ignored):

- `klassenmember/klassenmember.py` → `names/members.txt`
- `methodensignaturen/methodensignaturen.py` → `names/method-signatures.txt` (run after `klassenmember.py`)
- `bezeichnerliste/bezeichnerliste.py` → `tools/bezeichnerliste/bezeichner-komplett.txt`, the list of all
  identifiers the plugin recognizes, used to check which help pages exist.

`dbgp-probe/dbgp_probe.py` is a development tool for the planned debugger: it starts
`AutoHotkey64.exe /Debug=127.0.0.1:port` on test scripts, sends DBGp commands and logs the raw traffic, to observe
how the engine behaves (written from the DBGp specification only).

`publish/publish.py` builds the public repository: it exports a branch with `git archive` (without the files marked
`export-ignore`) into a local clone of the public repository and commits it there, without the private history.

## Tests

- `AhkParsingTest` (`ParsingTestCase`): parses small inputs and checks the tree and the errors. Every input runs
  in its own thread with a timeout to catch endless loops in the parser.
- `AhkPluginTest` (`BasePlatformTestCase`): the whole plugin - highlighting with `checkHighlighting`, completion,
  parameter info, navigation, formatting, help, run configuration, live templates. Tests for `#Include` use real
  files in a temporary directory.

`./gradlew test` regenerates lexer and parser first. Test reports are in `build/test-results/test/*.xml`.

Continuous integration: `.github/workflows/build.yml` (Windows runner) runs `test`, `buildPlugin` and
`verifyPlugin -PverifyRecommendedIdes=true`. Without the property, `verifyPlugin` only checks the target IDE
(`build.gradle.kts`, `pluginVerification`). `buildPlugin` includes `buildSearchableOptions`, which starts a headless
IDE and opens every settings page; bundle texts carry markers there, so code must not compare file values with
`AhkBundle` texts (see `AhkCaseMode`).

## Pitfalls

Things that have caused bugs before:

- **Grammar-Kit**: a rule with an empty operator (only predicates) must guarantee progress, otherwise the Pratt
  loop never ends. A binary Pratt rule is committed after its operator; ambiguous operators (`*` in `f(args*)`,
  `?` in `f(x?)`) need lookaheads. `recoverWhile` is evaluated after every match, not only after a failure, and
  ends a `( ... )*` loop when the element fails. Rule names must not equal token names. Tokens with the same
  value produce only one constant.
- **Lexer**: for equally long matches the first rule wins, so special lookahead rules for whitespace must come
  before the general whitespace rule.
- **Formatter**: use `relativeToDirectParent=false` for indents; bodies on the same line as their head get no
  own indent. `CodeStyleManager.reformat(PsiElement)` inserts empty lines at `CRLF` tokens (the action
  *Reformat Code* is not affected).
- **Extensions** must not use static initialization; put tables into holder classes.
- **Platform code that must run in the background** (e.g. sticky lines) must not be called on the EDT in tests.
- **Files outside the project** get no annotators unless they belong to a library.
- **Line endings**: the files are mixed CRLF/LF; keep the existing line ending when editing.
