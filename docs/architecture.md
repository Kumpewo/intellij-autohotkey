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
- **Language level** (`parser/AhkLanguageLevel`: AutoHotkey v2.0 or v2.1) of a file comes from its `#Requires`,
  otherwise from the settings. Name lists, signatures, values, options and help are loaded per level
  (`AhkNames.of(element)`, `AhkCommandInfo`, `AhkHelpRepository`); the data sets of v2.1 lie in the subfolders
  `v2.1/` (see [Resources](#resources)). The parser reads the level once at the start of the file
  (`AhkParserUtil.otherVersion`); syntax of v2.1 goes through `<<requiresV21 …>>` in `Ahk.bnf`
  (`AhkParserUtil.requiresV21`): parsed at every level, but an error "Requires AutoHotkey v2.1" in v2.0, so the
  tree stays the same. `struct` stays an `IDENTIFIER` in the lexer (no level there); in v2.1
  `AhkParserUtil.structKeyword` remaps it to `KEYWORD_STRUCT` at the start of a definition (`struct Name`).
- **Function definition expressions** (v2.1, `x := f() { ... }`): the element `FUNC_EXPR` holds a normal `FUNC`
  (the fake rule `func` in `Ahk.bnf` defines the PSI of all functions, `func_def` = statement, `func_literal` = in an
  expression, name optional), so navigation, locals (`AhkLocals`: the function is a scope), parameter info and the
  formatter treat it like any function; `AhkDefinitions.isFunctionExpression` tells them apart (never a method,
  `ownerClass` is `null`). `AhkParserUtil.funcExprStart` decides by raw lookahead (name directly before `(`,
  matching `)`, then `{`; line breaks before `{` only inside brackets, `enclosed`/`inParens`; not in the head of a
  control statement, `controlHead`, unless enclosed there), `funcBody` parses the body in a fresh state (no
  parentheses, deref or head around it). `AhkFuncExprAnnotator` reports it outside v2.1 and as a whole statement.
- **Typed properties** (v2.1, `x : Int32 := 0`, `union { ... }`): only in the body of a class, which the grammar
  parses with its own rule `class_block` (element type `BLOCK` as every block, with the items `typed_decl_stmt` and
  `union_block` in addition). `typed_decl_stmt` holds `TYPED_PROPERTY` elements and normal expressions (mixed
  lines `x : Int32, y := 2`); `AhkParserUtil.typedDeclStart` decides by lookahead (name and `:` at the start or
  after a comma outside brackets, not at the end of the line, which is a label). The type (`TYPE_SPEC`) is parsed
  by `AhkParserUtil.typeExpr` as `expr` with the priority of the postfix operators (only calls, index and member
  access), so `:= initializer` is not swallowed. `AhkParserUtil.unionKeyword` remaps `union` to `KEYWORD_UNION`
  only if its block starts with a typed property (otherwise a property named `union`). `AhkDefinitions.fields`
  returns typed properties as fields (also inside union blocks). `AhkTypedPropertyAnnotator` reports v2.0 and the
  content and place of union blocks.
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
| `parser/AhkVersion` | evaluates `#Requires AutoHotkey ...`: language level it asks for, requirement for the interpreter |
| `parser/AhkLanguageLevel` | language level of a file (`OTHER` = v1, `V2_0`, `V2_1`), cached; `findRequires` = the deciding `#Requires`; `AhkParserUtil.languageLevel` for the parser |
| `level/` | shows the language level: `AhkLevelInfo` (level + line of the deciding `#Requires`), status bar widget `AhkLevelWidgetFactory`/`AhkLevelWidget` (`EditorBasedWidget`, updated on editor selection and PSI changes in a non-blocking read action; click = go to `#Requires` or open the settings), `AhkLevelDecorator` (`projectViewNodeDecorator`: location string `v2.1` after `.ahk` files, setting `showLevelInProjectView`) |
| `AhkResources` | loads data sets per language level: v2.0 file plus difference of the level, or a complete copy |
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

Continuation sections outside a string (`IN_CS_OPEN`, `IN_CS`, `IN_CS_LINESTART` ...): the line break before a
line that starts with `(` (no other parenthesis on it) is whitespace; `(` and `)` stay `L_PAREN`/`R_PAREN`, so the
section is parsed as a `PAREN_EXPR`; its options are a `CONTINUATION_OPTIONS` token (in `AhkTokenSets.COMMENTS`);
inside it a string may span lines up to a line starting with `)`. `AhkParserUtil.spacedWithoutLineBreak` accepts the
line break before such a `(` (`MsgBox⏎(…)` is a concatenation like `MsgBox (…)`). The formatter treats the section
as a leaf with read-only spacing before it (`AhkBlock.isContinuationSection`): its text never changes. Block
comments end at `*/` only at the start or end of a line (`atLineStart`/`atLineEnd` in `Ahk.flex`, same tokens as
before for the formatter of doc comments).

### Expressions and statements

Expressions form a Pratt hierarchy (`expr` in `Ahk.bnf`); each operator is its own PSI element (`AddExpr`,
`MemberExpr`, `ConcatExpr` ...). Implicit concatenation (`a b`) is an operator without a token; its rule checks
the whole operand by lookahead first, because a token-less operator whose operand fails would make the Pratt
loop spin forever.

There is no symbol table: `f (x)` is always a concatenation and `f(x)` a call; a command without parentheses
(`MsgBox "x"`) is recognized by the token after the name.

Every function definition is one element type `FUNC` (`fake func` in `Ahk.bnf`): a function or method as a statement
(`func_def`) and a named fat arrow function inside an expression (`arrow_func` in `ARROW_FUNC_EXPR`,
`sumfn := Sum(a, b) => a + b`). `AhkParserUtil.arrowFuncStart` checks by a linear scan of the raw tokens that `=>`
follows the matching `)`, so an ordinary call is not parsed twice.

Line continuation is mostly the lexer's job (above); two cases are left to the grammar or need a special lexer rule:
the `:` of a ternary at the end of a line (`ternary_expr` allows `CRLF*` after `COLON`, because a `:` at the end of a
line is also a label or a `case`), and a line starting with `.Name` (in `CONT_START`: member access continuing a
method chain, unlike `. ` which is a concatenation). Hotkeys for a quote key (`^+"::`) have their own lexer rule
(`HOTKEY_QUOTE`), because the general hotkey text excludes quotes.

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
  `nested-classes`, `keys`, `members`), one set per language level (`AhkNames.get(level)`,
  `AhkNames.of(element)`), on first use. The tables live outside of the extension classes because extension classes
  must not use static initialization and annotators run in parallel.
- Further annotators each check one rule: `AhkBlockBraceAnnotator` (nothing after `{`), `AhkElseBodyAnnotator`,
  `AhkBadCommentAnnotator`, `AhkDeclAnnotator` (`global` without a name), `AhkModuleAnnotator` (`#Module`/`#Import`: v2.1 only, where they
  are allowed, module name; the arguments of `#Import` are normal tokens, see `Ahk.flex`, and the element
  `IMPORT_SPEC` in the grammar), `AhkParameterOrderAnnotator`
  (mandatory parameter after an optional one outside v2.1; the syntax is the same at both levels, therefore no
  parser rule), `AhkFuncExprAnnotator` (function definition expression: v2.1 only, not a statement on its own
  except after a hotkey), `AhkTypedPropertyAnnotator` (typed property and union block: v2.1 only, union block only
  directly in a class body and only with typed properties), `AhkVersionAnnotator` with the quick fix
  `AhkAddRequiresFix`, `AhkTypeDeclarationAnnotator`. `AhkAddRequiresFix.lineFor(level)` gives the line for the
  level of the file (= default level): v2.0 `#Requires AutoHotkey v2.0`, v2.1 the version from the folder name of
  the v2.1 interpreter (`v2.1-alpha.39`), otherwise `v2.1-`. The intention `AhkAddRequiresIntention` offers the same
  `#Requires` lines anywhere in a file without `#Requires` except in the line of the hint (no double entry); its
  subclass `V1` (`#Requires AutoHotkey v1.1`, first) only when `AhkVersionDetector` says v1.
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
  with groups `@Name`; `options(...)` with the rule `Class.Method<FirstArgument>`), one set per language level, and
  builds signatures of own functions and methods from their parameter lists.
- `AhkParameterInfoHandler` shows the forms for the owner found by `AhkArguments`.
- `AhkTypedHandler` opens the popup after `.` and `#` (in an include line also after `<`, quotes, `\`, `/`), the parameter info after `(`, and types `%` in pairs;
  `AhkQuoteHandler` types quotes in pairs.

## Navigation (`navigation/`, `include/`)

- `AhkDefinitionReferenceContributor` attaches an `AhkDefinitionReference` to free-standing names, command
  names, names after the dot and the names after `extends`. The reference is poly-variant: it collects matching
  functions, classes and properties in the file and its directly included files. Without a definition but with a
  help page, the target is an `AhkHelpTarget`, which opens the quick documentation.
- Local variables and parameters: `psi/AhkLocals` collects per scope (function, accessor, property,
  fat-arrow function; cached per PSI modification) the parameters, `local`/`static`/`global` declarations,
  assigned names and nested functions, and resolves a name by the rules of AutoHotkey v2 (closures, static nested
  functions, assume-global). Where a free-standing name, command name or definition (parameter, `decl_item`,
  `for_vars`, `catch ... as`, `x => ...`) is local, `AhkDefinitionReferenceContributor` attaches an
  `AhkLocalReference` instead; its target `AhkLocalVariable` (a `FakePsiElement`, equal by scope and name) has a
  `LocalSearchScope` of the owning function, so `AhkReferencesSearcher` only searches there.
  `AhkRefactoringSupportProvider` allows in-place rename for it; `AhkRenameProcessor` leaves it to the platform's
  default processor. Every occurrence, also the definition, is a reference, so `setName` does nothing. The name
  after `@param` in a function's doc comment carries an `AhkDocParamReference` (provider on AutoHotkey comments;
  the searcher adds it explicitly, because the comment lies outside the function).
- `AhkLabelReferenceContributor` / `AhkLabelReference`: targets of `goto`, `break`, `continue`.
- `AhkInclude` resolves `#Include` arguments (library folders, built-in variables, current include directory);
  `<Name>` by the language level of the file (`library`: v2.0 with the `_` rule, v2.1 own directory first and
  exact name before `Name.ahk`; standard Lib next to the interpreter of that level, `globalLibraryDirectories`
  without a level = all levels for the library "AutoHotkey Lib");
  `AhkIncludeReferenceContributor` / `AhkIncludeReference` open the file, `AhkIncludeDirectoryTarget` shows a hint
  for a directory.
- `AhkLibraryRootsProvider` registers the global Lib folders as a synthetic library, because the platform runs no
  annotators on files outside of the project and its libraries.
- `AhkReference` / `AhkReferenceContributor` are an early draft and are not registered.
- `AhkFindUsagesProvider` (`lang.findUsagesProvider`) enables Find Usages for `AhkNamedElement`s and
  `AhkFieldTarget` (which reports the AutoHotkey language for this); `AhkTargetElementEvaluator`
  (`targetElementEvaluator`) makes the name of a field in its defining statement (`x := 0`, `x : Int32`) the
  `AhkFieldTarget`, so Find Usages and Rename also work at the definition; `AhkReferencesSearcher` (`referencesSearch`)
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
- Rename: `AhkRenameProcessor` (`renamePsiElementProcessor`) forces the preview, finds code occurrences with
  `AhkReferencesSearcher.search(..., transitive = true)` (also indirectly including files; references there are
  checked with `AhkDefinitionReference.definitionsIn` over the file's whole include closure), adds occurrences in
  comments and strings as `NonCodeUsageInfo` in `findCollisions` (the platform's own search needs the word index)
  and reports conflicts. `setName` of the definitions and `handleElementRename` of the references replace the text
  of one leaf (`psi/AhkRenames`). `AhkNamesValidator` (`lang.namesValidator`), `AhkRefactoringSupportProvider`
  (in-place rename only for local variables), `AhkRenameHintHandler` (`renameHandler`: a hint where the platform
  finds nothing to rename, otherwise Shift+F6 silently does nothing) and `AhkRenameVeto` (help and directory targets) complete it. The searcher enumerates
  AutoHotkey files by walking the content roots and additional libraries, not through the file type index (in tests
  the index queried from the background thread did not show files of a content root added in the same run).
- `AhkHighlightUsagesHandlerFactory` (`highlightUsagesHandlerFactory`): occurrences of the name under the caret
  (automatic identifier highlighting and Ctrl+Shift+F7). Name-based like navigation: every `IDENTIFIER` with the
  same name, ignoring case (local variables: only the same variable, `AhkLocals.occurrences`; other names skip
  local variables); definitions and assignment targets are write occurrences. `highlightReferences()` is
  false, so the platform does not add its own reference search.

## Modules (`module/`, v2.1)

- `AhkModules` is the one place that knows the namespaces of AutoHotkey v2.1 modules; everything else asks it, and
  only for v2.1 files (`isActive`), so v2.0 files keep "the file and its direct includes". `regions` splits a file at
  its top-level `#Module` directives (first region `__Main`); `parts(Module)` collects the regions of a module in the
  group of its home file (the file itself plus the regions of its directly included files: the first region of an
  included file belongs to the module of the `#Include` line). `imports` parses `#Import` (`Import`, `Item`; an
  import inside a function only applies there). `resolveModule` finds the loaded module: `AHK` (built-in), a
  `#Module` of the group, `"Path:Name"`, or a module file in the search path (`searchDirectories`: folder of the
  file, folder of the module's home file, `AhkInclude.libPathDirectories` = `AhkLibPath` or user/standard library;
  `Name`, `Name\__Init.ahk`, `Name.ahk`). `exists` checks the disk for the "Module not found" error (a file need
  not be in the VFS yet).
- `resolve(context, name, finder, variables)`: own definitions of the module, then explicit imports, then wildcard
  imports (last first); `exports(module, name, ...)` for `X.Name` and `{Name}` (definitions, global variables, all
  explicit imports, wildcard imports with `Export`); `wildcardExports` for `{*}` (no `_` names, only imports with
  `Export`). A `Finder` (`AhkDefinitionReference.finder(kind, name, outer)`) gives the matching definitions of a
  whole file; `definitions` keeps those inside the module's parts. `Result.module` is set when the name stands for
  a module; `qualifierModule` uses it for `X.` and `X.Y.`. Results and caches depend on
  `PsiModificationTracker.MODIFICATION_COUNT` and `AhkSettings.LANGUAGE_LEVEL_TRACKER`.
- `AhkModuleSymbol` (`FakePsiElement`): target for names without own PSI element - `MODULE` (the name in the
  `DIRECTIVE_ARGS` of `#Module`), `ALIAS` (identifier after `as`), `VARIABLE` (first assignment or `global`
  declaration of a module variable, navigation only). Modules and aliases are definitions for
  `AhkReferencesSearcher` (Find Usages, Rename) and are found at the caret by `AhkTargetElementEvaluator`.
- `AhkModuleReferenceContributor`: `ModuleReference` on `import_module` (only `Name` in `"Path:Name"`; target the
  `#Module` symbol or the module file) and `ImportReference` on the first identifier of `import_item` (target from
  `exports`; for `AHK` the help). Both rules carry `AhkReferenceHostMixin` in the grammar.
- Users: `AhkDefinitionReference` (`moduleDefinitions`: free names and base classes by module, `X.Name` and
  `extends X.Base` by exports, `AHK.Name` = help), `AhkLocals.isFunctionOrClass`, `AhkCompletionContributor`
  (`addModuleScope`, `addModuleMembers`, `addModuleNames`, `addImportableNames`), `AhkCommandInfo`
  (`signatures(PsiElement, name)`, `moduleSignatures`), `AhkAnnotator.identifierColor` (`hiddenByModule`: built-in
  names the module defines or imports, `ownNames`; `moduleLeftOfDot`), `AhkModuleAnnotator` (checks),
  `AhkReferencesSearcher` (candidate files also import the definition's file; the transitive name check of Rename
  is skipped in files with modules), `AhkStructureViewElement`/`AhkStructureSorter` (module nodes) and
  `AhkFoldingBuilder` (module regions).
- The DBGp engine of v2.1-alpha.39 answers the *Global* context always for the module of the innermost frame
  (`-d` is ignored there) and shows a `Module` object without its exports; the debugger has no module-specific
  code.

## Help (`documentation/`)

- `AhkHelpRepository` knows which identifiers have help: the lookup files `help/lookup/docs.txt` (help shipped
  with AutoHotkey) and `help/lookup/extra.txt` (own pages), each line `word<TAB>path<TAB>anchor[<TAB>class]`
  (path relative to `help/`, anchor = `id` of a direct child of `<body>` or `<null>`). `docs.txt` is searched
  first. Per language level: for v2.1 the lines of `help/lookup/v2.1/docs.txt` replace all entries of their word,
  and the pages come from `help/v2.1/` (`Topic.root`). It loads pages as resources (path in lower case), cuts the
  page between `<body>` and the anchor,
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
- `AhkHelpAction` (menu *Help > AutoHotkey v2.0 Help*) and `AhkHelpAction.V21` (*AutoHotkey v2.1 Help*) call
  `AhkExternalHelp.openDocs(project, level)`: the command line of the settings with the key `/docs/index.htm`,
  otherwise `hh.exe` with `findChm()` / `findChmV21()`, otherwise `AhkBundledDocs` copies the help shipped with the
  plugin (`help/docs/` or `help/v2.1/docs/`, listed from the plugin JAR) to `<temp>/intellij-autohotkey/docs-<version>/`
  (reused while the JAR is unchanged, stamp file `.stamp`) and opens `index.htm` in the browser.
- In a compiler directive `;@Ahk2Exe-...` the help key is `@Ahk2Exe` (`AhkDocumentationProvider.pragmaKey`), an
  entry of `docs.txt` that points to `/docs/misc/Ahk2ExeDirectives.htm`.
- Doc comments: `AhkDocComment.of(definition)` finds the `/** */` before a function, method or class (the
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
documentation (currently v2.0.31) and can be replaced by a newer one at any time, `v2.1/docs/` the same for
AutoHotkey v2.1 (currently v2.1-alpha.39), `styles/` holds the stylesheets
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
functions, properties, labels; skips blocks; the fields of a class as `AhkFieldTarget` nodes from
`AhkDefinitions.fields`, typed ones with their type as location string), sorted by `AhkStructureSorter` (fields in
the order of the code, the layout of a struct). `AhkBreadcrumbsProvider` provides breadcrumbs
and, through the platform, sticky lines. `AhkFoldingBuilder` (`lang.foldingBuilder`, dumb-aware, syntax tree
only) folds brace pairs, block comments (the lexer returns them in several `BLOCK_COMMENT` tokens), continuation
sections, runs of comment and `#Include` lines and `;region`/`;endregion` and `;{`/`;}` regions. The platform's
custom folding providers only know `//`, `#` and `/*` as comment prefixes, so the regions are recognized here.

## Run and settings (`run/`, `settings/`)

- `AhkSettings` is an application-level light service (`@Service`, stored in `autohotkey.xml`): AutoHotkey path,
  external help command, trigger of the automatic validation, Ahk2Exe path and compression, language level of
  files without `#Requires` (`LANGUAGE_LEVEL_TRACKER` for cached levels), automatic detection
  of the installation, the launcher and Ahk2Exe (`detectAhk2Exe`). `findAutoHotkey(script)` replaces the launcher
  by the v2 interpreter next to it (`v2Interpreter`) if the script requires AutoHotkey 2.0
  (`AhkVersion.scriptRequirement`, read from the saved file with `AhkCompiler.readDirectives`); used by running and
  validating. `scriptTarget(script)` gives the language level of the saved script (`#Requires`, otherwise the
  default level) and the bitness; for level v2.1 `findAutoHotkey(script)` and `findInterpreter(script)` return the
  v2.1 interpreter (`findAutoHotkeyV21`: configured path `autoHotkeyV21Path`, otherwise the newest version folder
  `v2.1...` of the installation or entry of `UX\installed-files.csv`, `detectAutoHotkeyV21Dir`, compared with
  `AhkVersion.compareVersions`), never the launcher; `notFoundMessage(script)` names the missing program.
- `AhkSettingsConfigurable` is the settings page; changing the path updates the library roots, changing the
  language level parses the open files again.
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
- `AhkAhk2ExeHeader` builds the head of compiler directives as live template (placeholders for name, version
  in `;@Ahk2Exe-SetVersion`, description, copyright; values without quotes, aligned in one column; base
  `AutoHotkey32/64.exe` after `#Requires`) and inserts it below the first `#Requires`
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

## Installer and versions (`install/`)

- `AhkInstallAction` ("Deploy to Local Target...", Code menu, context menus) calls `AhkInstaller.install`: target as
  for Validate/Compile (`AhkValidator.target`), `settings.ahk-plugin.ini` next to the script, `prepare` in a
  background task (reads the INI, plans, asks Git for uncommitted changes), `AhkInstallDialog` (always; prominent
  notice for `RequiresAdmin`, `Message`, overview = compile step and the copy sections without comments, warnings,
  link "More..." to `/extra/installer.htm#Uncommitted`), then `execute` in a background task: compile
  (`AhkCompiler.run`, then the plan is created again because the executable exists now), copy; report in a console of
  the Run tool window. Problems before the start are collected and shown together; nothing happens then. No commit,
  no tag.
- `AhkInstallConfig` reads `settings.ahk-plugin.ini` (sections `[Install]`, `[Overwrite]`, `[KeepExisting]`,
  `[Version]`; keys case-insensitive, `;` comments only at the line start, one pair of quotes removed, duplicates and
  unknown keys are errors); `AhkInstallPlan` resolves `Destination`, `%DESTINATION%`, environment variables of the
  IDE process, `%%`, `` `n `` in `Message`, sources (no `..`, wildcards only in the file name; `willExist` = the
  executable that the compilation creates) and targets (folder rules) into `Step`s.
- `AhkCreateSettingsAction` ("Create settings.ahk-plugin.ini") and the missing-file question of the installer call
  `AhkSettingsFile.create`: the resource `install/settings.ahk-plugin.ini` (all options commented out and explained,
  `{tool}` = script name without extension, CR LF) is written next to the script in a write command (into the
  document if one is loaded) and opened; an existing file only after confirmation.
- `AhkSetVersionAction` ("Set Version...") calls `AhkSetVersion.run`: `prepare` in the background (version line,
  `TagPrefix` from `[Version]` if the INI exists, Git state, existing tags `<prefix>/*`), `AhkSetVersionDialog`
  (patch/minor/major/other, commit and tag check boxes, message; validates the version and that the tag is new),
  then the value is replaced in the document (`WriteCommandAction`, undoable) and saved, and `commitAndTag` runs in
  the background (`check-ref-format` first, then commit of the script only, annotated tag); result as notification.
- `AhkVersionDirective`: finds `;@Ahk2Exe-SetVersion` as Ahk2Exe does (trimmed line, any case, ` ;` comment cut
  off, block comments and `IgnoreBegin`/`IgnoreEnd` skipped, `/*@Ahk2Exe-Keep` counts), `bump`, `isValidVersion`,
  `tagName`.
- `AhkInstallGit`: `git` on the command line (no Git4Idea) in the tool folder: `status --porcelain` (folder, one file,
  other files via `:(exclude)`), `check-ref-format`, `rev-parse --verify`, `add -- <file>` +
  `commit --only -- <file>`, annotated tag on HEAD.
- `AhkInstaller.compiledExe` guesses the output file (`;@Ahk2Exe-ExeName`, otherwise `<script>.exe`) for the
  warning "executable older than the scripts".

## Debugger (`debug/`, in progress)

The debugger talks DBGp with the engine built into AutoHotkey v2 (`AutoHotkey64.exe /Debug=127.0.0.1:port`). It
follows the DBGp specification; the observed behavior of the engine is covered by the tests (`DbgpTest`,
`AhkDebugTest`).

- `AhkDebugRunner` (`programRunner`, debug executor, AutoHotkey run configurations) opens a `DbgpServer`, starts the
  v2 or v2.1 interpreter (`AhkSettings.findInterpreter`, never the launcher) with `/ErrorStdOut /Debug=…` and starts the
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
  children page by page with `tooManyChildren`, total for Array/Map from `Length`/`Count` plus the non-entry
  children of page 0 (`<base>`, in v2.1 also inherited properties), otherwise `numchildren`, which v2.1 does not
  send (`DbgpProperty.numChildren` = -1: "more" link without a number); "Set Value"; full value
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
| `names/keyword-case.txt` | keyword groups and spellings for case adjustment (all language levels) | by hand |
| `names/v2.1/*.txt` | AutoHotkey v2.1: differences of the lists, `signatures.txt` and `values.txt` (see below); `members.txt` and `method-signatures.txt` complete | by hand / generated from the v2.1 help |
| `help/lookup/v2.1/docs.txt` | AutoHotkey v2.1: differences of the help lookup | by hand |
| `messages/AhkBundle.properties` | all visible texts | by hand |
| `colorSchemes/AhkDefault.xml` | default colors | by hand |

The list format is simple: names separated by line breaks, spaces or commas, `;` starts a comment, case does not
matter. A new name in a list needs no code change.

A difference file of v2.1 (`AhkResources`) holds only what is new or different: in a name list a name is added and
`-Name` removes it; in `signatures.txt`, `values.txt`, `options.txt` and the help lookup a line replaces all v2.0
lines with the same key (signature and lookup: the name; values and options: `Name Position` or `@Group`), and
`-Key` removes them. Without a difference file v2.1 uses the v2.0 file.

## Tools (`tools/`)

Maintainer tools; they are not part of the published repository (`export-ignore` in `.gitattributes`). Python
scripts that read the AutoHotkey help under `src/main/resources/help/docs/` (class tree `objlist.htm`, linked
pages such as `lib/Array.htm`; the case of file names is ignored):

- `klassenmember/klassenmember.py` → `names/members.txt`
- `methodensignaturen/methodensignaturen.py` → `names/method-signatures.txt` (run after `klassenmember.py`)
- both with `--level 2.1`: from `help/v2.1/docs/` to `names/v2.1/`
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
- `module/AhkModuleTest` (`BasePlatformTestCase`): v2.1 modules with real files in a temporary content root
  (navigation, module files and `AhkLibPath` via `AhkInclude.environment`, Find Usages, Rename, completion,
  parameter info, highlighting, checks, structure view, folding).

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
