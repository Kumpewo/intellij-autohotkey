package de.kumpewo.autohotkey.completion;

import de.kumpewo.autohotkey.AhkBundle;
import com.intellij.codeInsight.completion.CompletionContributor;
import com.intellij.codeInsight.completion.CompletionInitializationContext;
import com.intellij.codeInsight.completion.CompletionParameters;
import com.intellij.codeInsight.completion.CompletionResultSet;
import com.intellij.codeInsight.completion.CompletionUtilCore;
import com.intellij.codeInsight.completion.InsertHandler;
import com.intellij.codeInsight.completion.util.ParenthesesInsertHandler;
import com.intellij.codeInsight.lookup.LookupElement;
import com.intellij.codeInsight.completion.PrioritizedLookupElement;
import com.intellij.codeInsight.lookup.LookupElementBuilder;
import com.intellij.icons.AllIcons;
import com.intellij.openapi.editor.colors.TextAttributesKey;
import com.intellij.psi.PsiElement;
import com.intellij.psi.PsiFile;
import com.intellij.psi.tree.IElementType;
import com.intellij.psi.util.PsiTreeUtil;
import com.intellij.application.options.CodeStyle;
import de.kumpewo.autohotkey.formatter.AhkCodeStyleSettings;
import de.kumpewo.autohotkey.formatter.AhkKeywordCase;
import de.kumpewo.autohotkey.highlighting.AhkColors;
import de.kumpewo.autohotkey.highlighting.AhkNames;
import de.kumpewo.autohotkey.include.AhkInclude;
import de.kumpewo.autohotkey.module.AhkModuleSymbol;
import de.kumpewo.autohotkey.module.AhkModules;
import de.kumpewo.autohotkey.psi.*;
import de.kumpewo.autohotkey.types.AhkTypeDeclarations;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import javax.swing.*;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Code completion (auto popup, Ctrl+Space) by context:
 * <ul>
 *   <li>directive (token DIRECTIVE_NAME, with "#"): directives.txt</li>
 *   <li>after the dot: methods/properties (methods.txt, properties.txt, plus those of the own classes); if exactly
 *       an outer built-in class is on the left (Gui.), its nested classes first; if the name on the left is
 *       declared with ;@declare-type or @param {Type}, only the members of its classes (plus those of the own classes);
 *       never meta members starting with "__" (__New, __Item ...)</li>
 *   <li>directly after "Loop": Files/Parse/Read/Reg, plus the free-standing names</li>
 *   <li>free-standing: local variables and parameters of the function (first), built-in variables, classes,
 *       functions, own functions and classes of the file and of the
 *       files included directly with #Include (not recursive, as for navigation);
 *       at the start of a statement also the flow control keywords</li>
 *   <li>after goto/break/continue: only labels (provided by AhkLabelReference.getVariants)</li>
 *   <li>in a string of Send.../ControlSend... after "{": key names (keys.txt), with "}"</li>
 *   <li>parameter values (values.txt, AhkCommandInfo): in the string of an argument (CoordMode "Mo"), at a free
 *       argument position with quotes (CoordMode Mo, at the top of the list), in directives without</li>
 *   <li>options in an option string (options.txt, AhkCommandInfo.options): the word at the caret
 *       (MsgBox "x",, "YesNo Ic", MyGui.Add("Edit", "w200 Read"), Gui("+Res")); a "+"/"-" before it stays, an
 *       option with a value (w200) inserts only its prefix</li>
 *   <li>in a hotkey before "::" (token HOTKEY, e.g. "^F|::"): key names (keys.txt)</li>
 *   <li>argument of #Include/#IncludeAgain: files and directories (AhkIncludeCompletion)</li>
 *   <li>v2.1 modules ({@link AhkModules}): free-standing names only from the own module plus what it imports
 *       (in files with {@code #Module}/{@code #Import}); after a module ({@code X.}) only its exports, after
 *       {@code AHK.} the built-in names; in {@code #Import} the module names (modules of the group, module files
 *       of the search path, {@code AHK}) and in its list the names the module exports</li>
 *   <li>in a doc comment after "@" at the start of a line: the tags, after "@param ": the parameters without
 *       {@code @param} yet (AhkDocTagCompletion)</li>
 * </ul>
 * Functions and methods are inserted with "()" (caret in between). Case does not matter; the spelling of the list
 * is inserted. No suggestions in other comments and in other strings.
 * The roles correspond to highlighting (AhkAnnotator.identifierColor).
 */
public class AhkCompletionContributor extends CompletionContributor {

    /** Tables in a separate class: no static initialization in the extension class. */
    private static final class Tables {
        /**
         * Flow control keywords at the start of a statement. Inserted in the spelling from keyword-case.txt
         * according to the switch of the group (AhkKeywordCase.completionSpelling).
         */
        static final List<String> FLOW_KEYWORDS = List.of(
                "if", "else", "while", "loop", "for", "until", "return", "break", "continue",
                "try", "catch", "finally", "throw", "switch", "case", "goto");

        /**
         * Inserts "()" with the caret in between; if "(" already follows, only jumps there. Then shows the
         * parameter info: the "(" was not typed, so AhkTypedHandler does not see it.
         */
        static final InsertHandler<LookupElement> PARENS = (context, item) -> {
            ParenthesesInsertHandler.getInstance(true).handleInsert(context, item);
            com.intellij.codeInsight.AutoPopupController.getInstance(context.getProject())
                    .autoPopupParameterInfo(context.getEditor(), null);
        };

        /** Key name: appends "}" unless it is already there; caret after it. */
        static final InsertHandler<LookupElement> CLOSE_BRACE = (context, item) -> {
            com.intellij.openapi.editor.Document doc = context.getDocument();
            int tail = context.getTailOffset();
            if (tail >= doc.getTextLength() || doc.getCharsSequence().charAt(tail) != '}') {
                doc.insertString(tail, "}");
            }
            context.getEditor().getCaretModel().moveToOffset(tail + 1);
        };
    }

    /** Dummy identifier without a trailing space. */
    @Override
    public void beforeCompletion(@NotNull CompletionInitializationContext context) {
        // without a trailing space: "g.<dummy>" stays a member access, "#Inc<dummy>" a directive
        context.setDummyIdentifier(CompletionUtilCore.DUMMY_IDENTIFIER_TRIMMED);
    }

    /** Adds the suggestions for the context of the caret (see class comment). */
    @Override
    public void fillCompletionVariants(@NotNull CompletionParameters parameters, @NotNull CompletionResultSet result) {
        PsiElement leaf = parameters.getPosition();
        IElementType type = leaf.getNode().getElementType();
        if (type == AhkTypes.DIRECTIVE_NAME) {
            String prefix = leaf.getText().substring(0, parameters.getOffset() - leaf.getTextRange().getStartOffset());
            CompletionResultSet directives = result.withPrefixMatcher(prefix).caseInsensitive();
            for (String name : AhkNames.of(leaf).names("directives")) {
                directives.addElement(item(name, AhkBundle.message("completion.directive"), AllIcons.Nodes.Annotationtype, false));
            }
            return;
        }
        if (type == AhkTypes.STRING || type == AhkTypes.UNCLOSED_STRING) {
            addStringValues(leaf, parameters.getOffset(), result);
            addStringOptions(leaf, parameters.getOffset(), result);
            addKeyNames(leaf, parameters.getOffset(), result);
            return;
        }
        if (type == AhkTypes.DIRECTIVE_ARGS) {
            if (leaf.getParent() instanceof AhkDirective directive
                    && AhkIncludeCompletion.addPaths(directive, leaf, parameters.getOffset(), result)) return;
            addDirectiveValues(leaf, parameters.getOffset(), result);
            return;
        }
        if (type == AhkTypes.HOTKEY) {
            addHotkeyKeyNames(leaf, parameters.getOffset(), result);
            return;
        }
        if (type == AhkTypes.BLOCK_COMMENT) {
            AhkDocTagCompletion.add(parameters, result);
            return;
        }
        if (type != AhkTypes.IDENTIFIER) return;   // other comments, numbers ...
        CompletionResultSet out = result.caseInsensitive();

        PsiElement parent = leaf.getParent();
        IElementType parentType = parent.getNode().getElementType();
        if (parentType == AhkTypes.IMPORT_MODULE) {
            addModuleNames(leaf, out);
            return;
        }
        if (parentType == AhkTypes.IMPORT_ITEM) {
            if (parent.getFirstChild() == leaf) addImportableNames(leaf, out);
            return;
        }
        if (parentType == AhkTypes.MEMBER_EXPR) {
            if (isAfterDot(leaf) && !addModuleMembers(parent, out)) addMembers(parent, out);
            return;
        }
        if (parentType == AhkTypes.REF_EXPR) {
            PsiElement statement = parent.getParent();
            if (statement instanceof AhkGotoStmt || statement instanceof AhkJumpStmt) return;   // labels
            addQuotedValues(parent, out);
        } else if (parentType != AhkTypes.COMMAND_STMT) {
            return;   // definitions, keys, parameters, parts of dynamic names ...
        }

        PsiElement prev = prevCodeLeaf(leaf);
        IElementType prevType = prev == null ? null : prev.getNode().getElementType();
        AhkCodeStyleSettings caseSettings = CodeStyle.getCustomSettings(leaf.getContainingFile(), AhkCodeStyleSettings.class);
        if (prevType == AhkTypes.KEYWORD_LOOP) {
            for (String name : AhkNames.loopSubcommands()) {
                out.addElement(item(AhkKeywordCase.completionSpelling(name, caseSettings), AhkBundle.message("completion.loop"), null, true));
            }
        }
        if (isStatementStart(prevType)) {
            for (String keyword : Tables.FLOW_KEYWORDS) {
                out.addElement(item(AhkKeywordCase.completionSpelling(keyword, caseSettings), AhkBundle.message("completion.keyword"), null, true));
            }
        }
        addFreeStanding(leaf.getContainingFile(), leaf, out);
    }

    /**
     * Parameter values (values.txt) in a string that is a whole argument: CoordMode "Mo| or CoordMode("Mo|").
     * Prefix = text between the quote and the caret; only the value is inserted.
     */
    private static void addStringValues(PsiElement string, int offset, CompletionResultSet result) {
        PsiElement literal = string.getParent();
        AhkArguments.Owner owner = literal == null ? null : AhkArguments.ownerOfArgument(literal);
        if (owner == null) return;
        int index = AhkArguments.index(owner.element(), literal.getTextRange().getStartOffset());
        AhkCommandInfo.Values values = AhkCommandInfo.values(string.getContainingFile(), owner, index);
        if (values.values().isEmpty()) return;
        int caret = offset - string.getTextRange().getStartOffset();
        if (caret < 1) return;   // before the quote
        CompletionResultSet out = result.withPrefixMatcher(string.getText().substring(1, caret)).caseInsensitive();
        for (String value : values.values()) out.addElement(item(value, values.typeText(), null, true));
    }

    /**
     * Options in an option string: the word at the caret is the prefix (after the last space or tab inside the
     * string, without a leading "+" or "-", which stays). Options with a value ("w*" in options.txt) insert only
     * their prefix, shown as "w...". For a method whose first argument is a string literal, the options of
     * Class.Method&lt;FirstArgument&gt; apply (MyGui.Add("Edit", "|") = Gui.AddEdit).
     */
    private static void addStringOptions(PsiElement string, int offset, CompletionResultSet result) {
        PsiElement literal = string.getParent();
        AhkArguments.Owner owner = literal == null ? null : AhkArguments.ownerOfArgument(literal);
        if (owner == null) return;
        int index = AhkArguments.index(owner.element(), literal.getTextRange().getStartOffset());
        AhkCommandInfo.Values options = AhkCommandInfo.options(string.getContainingFile(), owner, index,
                firstStringArgument(owner));
        if (options.values().isEmpty()) return;
        int caret = offset - string.getTextRange().getStartOffset();
        if (caret < 1) return;   // before the quote
        String before = string.getText().substring(1, caret);
        String word = before.substring(Math.max(before.lastIndexOf(' '), before.lastIndexOf('\t')) + 1);
        if (word.startsWith("+") || word.startsWith("-")) word = word.substring(1);
        CompletionResultSet out = result.withPrefixMatcher(word).caseInsensitive();
        for (String option : options.values()) {
            boolean withValue = option.endsWith("*");
            String name = withValue ? option.substring(0, option.length() - 1) : option;
            if (name.isEmpty()) continue;
            out.addElement(LookupElementBuilder.create(name).withPresentableText(withValue ? name + "\u2026" : name)
                    .withTypeText(options.typeText()));
        }
    }

    /** Text of a string literal that is the first argument of a method call, otherwise null. */
    private static @Nullable String firstStringArgument(AhkArguments.Owner owner) {
        if (!owner.method()) return null;
        for (PsiElement child = owner.element().getFirstChild(); child != null; child = child.getNextSibling()) {
            IElementType type = child.getNode().getElementType();
            if (child instanceof com.intellij.psi.PsiWhiteSpace || type == AhkTypes.L_PAREN || type == AhkTypes.CRLF) continue;
            if (type != AhkTypes.LITERAL_EXPR) return null;
            com.intellij.lang.ASTNode string = child.getNode().findChildByType(AhkTypes.STRING);
            String text = string == null ? null : string.getText();
            return text == null || text.length() < 2 ? null : text.substring(1, text.length() - 1);
        }
        return null;
    }

    /**
     * Parameter values at a free argument position (CoordMode Mo|): inserted with quotes, at the top of the list.
     * The value is also found without the quote (Mo matches "Mouse").
     */
    private static void addQuotedValues(PsiElement refExpr, CompletionResultSet out) {
        AhkArguments.Owner owner = AhkArguments.ownerOfArgument(refExpr);
        if (owner == null) return;
        int index = AhkArguments.index(owner.element(), refExpr.getTextRange().getStartOffset());
        AhkCommandInfo.Values values = AhkCommandInfo.values(refExpr.getContainingFile(), owner, index);
        for (String value : values.values()) {
            LookupElementBuilder item = LookupElementBuilder.create("\"" + value + "\"").withLookupString(value)
                    .withPresentableText("\"" + value + "\"").withTypeText(values.typeText()).bold();
            out.addElement(com.intellij.codeInsight.completion.PrioritizedLookupElement.withPriority(item, 100));
        }
    }

    /** Parameter values in directives (#SingleInstance Fo|, #Warn All, Of|): without quotes. */
    private static void addDirectiveValues(PsiElement args, int offset, CompletionResultSet result) {
        PsiElement directive = args.getParent();
        if (!(directive instanceof AhkDirective)) return;
        com.intellij.lang.ASTNode name = directive.getNode().findChildByType(AhkTypes.DIRECTIVE_NAME);
        if (name == null) return;
        List<String> values = AhkCommandInfo.values(de.kumpewo.autohotkey.parser.AhkLanguageLevel.of(directive),
                name.getText(), AhkArguments.index(directive, offset));
        if (values.isEmpty()) return;
        String before = args.getText().substring(0, offset - args.getTextRange().getStartOffset());
        int start = before.length();
        while (start > 0 && !Character.isWhitespace(before.charAt(start - 1)) && before.charAt(start - 1) != ',') {
            start--;
        }
        CompletionResultSet out = result.withPrefixMatcher(before.substring(start)).caseInsensitive();
        for (String value : values) out.addElement(item(value, name.getText(), null, true));
    }

    /**
     * Key names (keys.txt) in a string of Send/SendInput/.../ControlSend, directly after an open "{":
     * Send "{Ent becomes Send "{Enter}". Other strings get no suggestions.
     */
    private static void addKeyNames(PsiElement string, int offset, CompletionResultSet result) {
        String before = string.getText().substring(0, offset - string.getTextRange().getStartOffset());
        int brace = before.lastIndexOf('{');
        if (brace < 0 || before.indexOf('}', brace) >= 0) return;
        String prefix = before.substring(brace + 1);
        if (!prefix.matches("[A-Za-z0-9_]*") || !isSendArgument(string)) return;
        CompletionResultSet keys = result.withPrefixMatcher(prefix).caseInsensitive();
        for (String name : AhkNames.of(string).names("keys")) {
            keys.addElement(item(name, AhkBundle.message("completion.key"), null, false).withInsertHandler(Tables.CLOSE_BRACE));
        }
    }

    /**
     * Key names in a hotkey: caret directly before "::" (or before " Up::"), e.g. type "::" first, put the caret
     * before it and start the name. With the dummy identifier "F<dummy>::" is a HOTKEY token. Prefix = letters/
     * digits/_ before the caret (modifiers like ^!+# and "a &amp; " before it don't belong to it).
     */
    private static void addHotkeyKeyNames(PsiElement hotkey, int offset, CompletionResultSet result) {
        String text = hotkey.getText();
        int caret = offset - hotkey.getTextRange().getStartOffset();
        String after = text.substring(caret);
        if (after.startsWith(CompletionUtilCore.DUMMY_IDENTIFIER_TRIMMED)) {
            after = after.substring(CompletionUtilCore.DUMMY_IDENTIFIER_TRIMMED.length());
        }
        if (!after.matches("(?is)(\\s+up)?::.*")) return;
        String before = text.substring(0, caret);
        int start = before.length();
        while (start > 0 && isKeyNameChar(before.charAt(start - 1))) start--;
        CompletionResultSet keys = result.withPrefixMatcher(before.substring(start)).caseInsensitive();
        for (String name : AhkNames.of(hotkey).names("keys")) keys.addElement(item(name, AhkBundle.message("completion.key"), null, false));
    }

    /** ASCII letter, digit or underscore. */
    private static boolean isKeyNameChar(char c) {
        return c == '_' || c < 128 && Character.isLetterOrDigit(c);
    }

    /** The string is an argument of a call or command whose name starts with Send/ControlSend. */
    private static boolean isSendArgument(PsiElement string) {
        for (PsiElement e = string.getParent(); e != null && !(e instanceof PsiFile); e = e.getParent()) {
            PsiElement name = null;
            if (e instanceof AhkCallExpr) name = e.getFirstChild();
            else if (e instanceof AhkCommandStmt) name = e.getFirstChild();
            if (name == null) continue;
            String text = name.getText().toLowerCase(Locale.ROOT);
            return text.startsWith("send") || text.startsWith("controlsend");
        }
        return false;
    }

    /**
     * Local variables and parameters of the function (also those shared with enclosing functions), built-in
     * variables, classes and functions (priority as in highlighting) and own definitions.
     */
    private static void addFreeStanding(PsiFile file, PsiElement leaf, CompletionResultSet out) {
        Set<String> seen = new HashSet<>();
        for (AhkLocals.Local local : AhkLocals.visible(leaf)) {
            String name = ownName(local.name(), leaf, seen);
            if (name == null) continue;
            out.addElement(PrioritizedLookupElement.withPriority(item(name,
                    AhkBundle.message(local.parameter() ? "completion.local.parameter" : "completion.local.variable"),
                    local.parameter() ? AllIcons.Nodes.Parameter : AllIcons.Nodes.Variable, false), 1));
        }
        AhkNames names = AhkNames.of(file);
        addBuiltins(names, "variables", AhkColors.AHK_BUILTIN_VARIABLE, AhkBundle.message("completion.variable"), AllIcons.Nodes.Variable, out);
        addBuiltins(names, "classes", AhkColors.AHK_BUILTIN_CLASS, AhkBundle.message("completion.class"), AllIcons.Nodes.Class, out);
        addBuiltins(names, "functions", AhkColors.AHK_BUILTIN_FUNCTION, AhkBundle.message("completion.function"), AllIcons.Nodes.Function, out);

        if (AhkModules.isActive(file) && AhkModules.usesModules(file)) {
            addModuleScope(file, leaf, seen, out);
            return;
        }
        for (PsiFile source : definitionFiles(file)) {
            String where = origin(source, file);
            for (AhkFunc func : PsiTreeUtil.findChildrenOfType(source, AhkFunc.class)) {
                if (isMethod(func)) continue;
                String name = ownName(func.getName(), leaf, seen);
                if (name != null) out.addElement(item(func, name, AhkBundle.message("completion.own.function", where), AllIcons.Nodes.Function)
                        .withInsertHandler(Tables.PARENS));
            }
            for (AhkClass cls : PsiTreeUtil.findChildrenOfType(source, AhkClass.class)) {
                if (PsiTreeUtil.getParentOfType(cls, AhkClass.class) != null) continue;    // nested
                String name = ownName(cls.getName(), leaf, seen);
                if (name != null) out.addElement(item(cls, name, AhkBundle.message("completion.own.class", where), AllIcons.Nodes.Class));
            }
        }
    }

    /**
     * v2.1 file with modules: the functions and classes of the own module (in the file and its includes), then the
     * names it imports (modules, list items, wildcard imports).
     */
    private static void addModuleScope(PsiFile file, PsiElement leaf, Set<String> seen, CompletionResultSet out) {
        AhkModules.Module module = AhkModules.moduleOf(leaf);
        for (PsiElement definition : AhkModules.definitions(module, source -> {
            List<PsiElement> result = new java.util.ArrayList<>();
            for (AhkFunc func : PsiTreeUtil.findChildrenOfType(source, AhkFunc.class)) {
                if (!isMethod(func)) result.add(func);
            }
            for (AhkClass cls : PsiTreeUtil.findChildrenOfType(source, AhkClass.class)) {
                if (PsiTreeUtil.getParentOfType(cls, AhkClass.class) == null) result.add(cls);
            }
            return result;
        })) {
            String name = ownName(((com.intellij.psi.PsiNamedElement) definition).getName(), leaf, seen);
            if (name == null) continue;
            String where = origin(definition.getContainingFile(), file);
            out.addElement(definition instanceof AhkFunc
                    ? item(definition, name, AhkBundle.message("completion.own.function", where), AllIcons.Nodes.Function).withInsertHandler(Tables.PARENS)
                    : item(definition, name, AhkBundle.message("completion.own.class", where), AllIcons.Nodes.Class));
        }
        for (AhkModules.Named named : AhkModules.importedNames(leaf)) {
            String name = ownName(named.name(), leaf, seen);
            if (name != null) out.addElement(moduleItem(named, name));
        }
    }

    /**
     * After {@code X.} where X is a module: its exports; after {@code AHK.} the built-in variables, classes and
     * functions. Returns false if the left side is no module.
     */
    private static boolean addModuleMembers(PsiElement memberExpr, CompletionResultSet out) {
        PsiElement qualifier = memberExpr.getFirstChild();
        if (qualifier == null || !AhkModules.isActive(memberExpr.getContainingFile())) return false;
        AhkModules.Module module = AhkModules.qualifierModule(qualifier,
                n -> de.kumpewo.autohotkey.navigation.AhkDefinitionReference.finder(
                        de.kumpewo.autohotkey.navigation.AhkDefinitionReference.Kind.FREE, n, null));
        if (module == null) return false;
        if (module.isBuiltin()) {
            AhkNames names = AhkNames.of(memberExpr);
            addBuiltins(names, "variables", AhkColors.AHK_BUILTIN_VARIABLE, AhkBundle.message("completion.variable"), AllIcons.Nodes.Variable, out);
            addBuiltins(names, "classes", AhkColors.AHK_BUILTIN_CLASS, AhkBundle.message("completion.class"), AllIcons.Nodes.Class, out);
            addBuiltins(names, "functions", AhkColors.AHK_BUILTIN_FUNCTION, AhkBundle.message("completion.function"), AllIcons.Nodes.Function, out);
            return true;
        }
        PsiElement leaf = PsiTreeUtil.getDeepestLast(memberExpr);
        Set<String> seen = new HashSet<>();
        for (AhkModules.Named named : AhkModules.exportedNames(module, false)) {
            String name = ownName(named.name(), leaf, seen);
            if (name != null) out.addElement(moduleItem(named, name));
        }
        return true;
    }

    /**
     * Module names after {@code #Import}: {@code AHK}, the modules of the group (not {@code __Main}) and the module
     * files of the search path ({@code Name.ahk}, a folder with {@code __Init.ahk}).
     */
    private static void addModuleNames(PsiElement leaf, CompletionResultSet out) {
        AhkDirective directive = PsiTreeUtil.getParentOfType(leaf, AhkDirective.class);
        AhkModules.Import imp = directive == null ? null : AhkModules.parse(directive);
        if (imp == null || imp.quoted()) return;
        PsiFile file = leaf.getContainingFile();
        Set<String> seen = new HashSet<>();
        out.addElement(item(AhkModules.BUILTIN, AhkBundle.message("completion.module.builtin"), AllIcons.Nodes.Module, false));
        seen.add(key(AhkModules.BUILTIN));
        for (String name : AhkModules.moduleNames(file)) {
            if (name.equalsIgnoreCase(AhkModules.MAIN) || ownName(name, leaf, seen) == null) continue;
            out.addElement(item(name, AhkBundle.message("completion.module"), AllIcons.Nodes.Module, true));
        }
        java.nio.file.Path self = AhkInclude.localPath(file);
        for (java.nio.file.Path dir : AhkModules.searchDirectories(imp, AhkModules.moduleOf(directive))) {
            java.io.File[] entries = dir.toFile().listFiles();
            if (entries == null) continue;
            java.util.Arrays.sort(entries);
            for (java.io.File entry : entries) {
                String name = null;
                if (entry.isFile() && entry.getName().toLowerCase(Locale.ROOT).endsWith(".ahk")
                        && (self == null || !entry.toPath().equals(self))) {
                    name = entry.getName().substring(0, entry.getName().length() - 4);
                } else if (entry.isDirectory() && new java.io.File(entry, "__Init.ahk").isFile()) {
                    name = entry.getName();
                }
                if (name == null || !AhkModules.isIdentifier(name) || ownName(name, leaf, seen) == null) continue;
                out.addElement(item(name, AhkBundle.message("completion.module.file", String.valueOf(dir.getFileName())),
                        AllIcons.Nodes.Module, false));
            }
        }
    }

    /** In the list of {@code #Import X {...}}: the names module X exports (for {@code AHK} the built-in names). */
    private static void addImportableNames(PsiElement leaf, CompletionResultSet out) {
        AhkDirective directive = PsiTreeUtil.getParentOfType(leaf, AhkDirective.class);
        AhkModules.Import imp = directive == null ? null : AhkModules.parse(directive);
        AhkModules.Module module = imp == null ? null : AhkModules.resolveModule(imp, AhkModules.moduleOf(directive));
        if (module == null) return;
        if (module.isBuiltin()) {
            AhkNames names = AhkNames.of(leaf);
            addBuiltins(names, "classes", AhkColors.AHK_BUILTIN_CLASS, AhkBundle.message("completion.class"), AllIcons.Nodes.Class, out);
            addBuiltins(names, "functions", AhkColors.AHK_BUILTIN_FUNCTION, AhkBundle.message("completion.function"), AllIcons.Nodes.Function, out);
            return;
        }
        Set<String> seen = new HashSet<>();
        for (AhkModules.Named named : AhkModules.exportedNames(module, false)) {
            String name = ownName(named.name(), leaf, seen);
            if (name != null) out.addElement(moduleItem(named, name).withInsertHandler(null));
        }
    }

    /** Lookup item for a name a module exports or imports: function (with "()"), class, variable or module. */
    private static LookupElementBuilder moduleItem(AhkModules.Named named, String name) {
        PsiElement target = named.target();
        PsiFile source = target instanceof PsiFile f ? f : target.getContainingFile();
        String where = source == null ? "" : AhkBundle.message("completion.origin.file", source.getName());
        if (named.module() != null || target instanceof PsiFile
                || target instanceof AhkModuleSymbol symbol && symbol.kind() == AhkModuleSymbol.Kind.MODULE) {
            return item(name, AhkBundle.message("completion.module"), AllIcons.Nodes.Module, false);
        }
        if (target instanceof AhkFunc) {
            return item(target, name, AhkBundle.message("completion.own.function", where), AllIcons.Nodes.Function)
                    .withInsertHandler(Tables.PARENS);
        }
        if (target instanceof AhkClass) return item(target, name, AhkBundle.message("completion.own.class", where), AllIcons.Nodes.Class);
        if (target instanceof AhkModuleSymbol symbol && symbol.kind() == AhkModuleSymbol.Kind.VARIABLE) {
            return item(name, AhkBundle.message("completion.own.variable", where), AllIcons.Nodes.Variable, false);
        }
        return item(name, AhkBundle.message("completion.imported", where), AllIcons.Nodes.Alias, false);
    }

    /**
     * Files with own definitions: the file itself, then the files included directly with #Include (not recursive,
     * as for navigation, AhkDefinitionReference).
     */
    private static List<PsiFile> definitionFiles(PsiFile file) {
        List<PsiFile> files = new java.util.ArrayList<>();
        files.add(file);
        files.addAll(AhkInclude.includedFiles(file));
        return files;
    }

    /** Origin for the type text: "(this file)" for the own file, otherwise "(name.ahk)". */
    private static String origin(PsiFile source, PsiFile file) {
        return source == file ? AhkBundle.message("completion.origin.this.file") : AhkBundle.message("completion.origin.file", source.getName());
    }

    /** All names of a built-in list in the given role; functions with "()". */
    private static void addBuiltins(AhkNames names, String file, TextAttributesKey role, String typeText, Icon icon,
                                    CompletionResultSet out) {
        for (String name : names.names(file)) {
            // name in several lists: only in the role that highlighting chooses too (variables > classes > functions)
            if (names.nonMember(name) != role) continue;
            LookupElementBuilder item = item(name, typeText, icon, false);
            out.addElement(role == AhkColors.AHK_BUILTIN_FUNCTION ? item.withInsertHandler(Tables.PARENS) : item);
        }
    }

    /**
     * After the dot: nested classes (Gui.Button), built-in members and members of the own classes. If the name on
     * the left is declared with ;@declare-type (or @param {Type} of the enclosing function), only the
     * instance members of its classes including base classes instead of all built-in members (type text = class that defines the member). If one of them is an own class,
     * the members of the other own classes are left out.
     */
    private static void addMembers(PsiElement memberExpr, CompletionResultSet out) {
        PsiElement qualifier = memberExpr.getFirstChild();
        AhkNames names = AhkNames.of(memberExpr);
        AhkTypeDeclarations.Declared declared = null;
        if (qualifier != null && qualifier.getNode().getElementType() == AhkTypes.REF_EXPR) {
            for (String name : names.nestedClassesOf(qualifier.getText())) {
                out.addElement(item(name, AhkBundle.message("completion.class"), AllIcons.Nodes.Class, true));
            }
            declared = AhkTypeDeclarations.lookup(qualifier, qualifier.getText());
        }
        Set<String> seen = new HashSet<>();
        PsiFile file = memberExpr.getContainingFile();
        if (declared != null) {
            for (AhkNames.Member member : AhkTypeDeclarations.members(file, declared)) {
                if (isMetaName(member.name())) continue;
                seen.add(key(member.name()));
                LookupElementBuilder item = item(member.name(), member.owner(),
                        member.method() ? AllIcons.Nodes.Method : AllIcons.Nodes.Property, false);
                // Order: first the members of the declared class, then one level deeper per base class
                // (within a level the IDE sorts alphabetically); all before other suggestions
                out.addElement(com.intellij.codeInsight.completion.PrioritizedLookupElement.withPriority(
                        member.method() ? item.withInsertHandler(Tables.PARENS) : item, 100 - member.depth()));
            }
        } else {
            for (String name : names.names("methods")) {
                if (!isMetaName(name) && seen.add(key(name))) {
                    out.addElement(item(name, AhkBundle.message("completion.method"), AllIcons.Nodes.Method, false)
                            .withInsertHandler(Tables.PARENS));
                }
            }
            for (String name : names.names("properties")) {
                if (!isMetaName(name) && seen.add(key(name))) {
                    out.addElement(item(name, AhkBundle.message("completion.property"), AllIcons.Nodes.Property, false));
                }
            }
        }
        // declared as an own class: only its members including base classes, not those of all own classes
        if (declared != null && AhkTypeDeclarations.hasOwnClass(file, declared)) return;
        PsiElement leaf = PsiTreeUtil.getDeepestLast(memberExpr);
        for (PsiFile source : definitionFiles(file)) {
            String where = origin(source, file);
            for (AhkFunc func : PsiTreeUtil.findChildrenOfType(source, AhkFunc.class)) {
                if (!isMethod(func)) continue;
                if (isMetaName(func.getName())) continue;
                String name = ownName(func.getName(), leaf, seen);
                if (name != null) out.addElement(item(func, name, AhkBundle.message("completion.own.method", where), AllIcons.Nodes.Method)
                        .withInsertHandler(Tables.PARENS));
            }
            for (AhkClassProperty property : PsiTreeUtil.findChildrenOfType(source, AhkClassProperty.class)) {
                com.intellij.lang.ASTNode id = property.getNode().findChildByType(AhkTypes.IDENTIFIER);
                if (id != null && isMetaName(id.getText())) continue;
                String name = ownName(id == null ? null : id.getText(), leaf, seen);
                if (name != null) out.addElement(item(name, AhkBundle.message("completion.own.property", where), AllIcons.Nodes.Property, false));
            }
            for (AhkClass cls : PsiTreeUtil.findChildrenOfType(source, AhkClass.class)) {
                for (de.kumpewo.autohotkey.psi.AhkDefinitions.Field field : de.kumpewo.autohotkey.psi.AhkDefinitions.fields(cls)) {
                    if (isMetaName(field.name())) continue;
                    String name = ownName(field.name(), leaf, seen);
                    if (name != null) out.addElement(item(name, AhkBundle.message("completion.own.field", where), AllIcons.Nodes.Field, false));
                }
            }
        }
    }

    /**
     * A function inside a class counts as a method (also one nested in a method); a function definition expression
     * never does (v2.1, {@code p := () { ... }} in a class body).
     */
    private static boolean isMethod(@NotNull AhkFunc func) {
        return PsiTreeUtil.getParentOfType(func, AhkClass.class) != null && !AhkDefinitions.isFunctionExpression(func);
    }

    /**
     * Meta members starting with "__" (__New, __Item, __Enum, __Get ...): they only serve as definitions
     * (constructor, [] access ...) and are not called directly, so they are in no completion.
     * As definitions in a class they are still colored normally.
     */
    private static boolean isMetaName(@Nullable String name) {
        return name != null && name.startsWith("__");
    }

    /** Name of an own definition, unless already offered or the name being typed. */
    private static @Nullable String ownName(@Nullable String name, PsiElement leaf, Set<String> seen) {
        if (name == null || name.contains(CompletionUtilCore.DUMMY_IDENTIFIER_TRIMMED)) return null;
        if (leaf != null && name.equals(leaf.getText())) return null;
        return seen.add(key(name)) ? name : null;
    }

    /** Lookup element with type text, icon and optionally bold. */
    private static LookupElementBuilder item(String name, String typeText, @Nullable Icon icon, boolean bold) {
        // The original spelling is inserted (strle -> StrLen); result.caseInsensitive() does the
        // case-insensitive matching. withCaseSensitivity(false) would keep the typed spelling.
        LookupElementBuilder item = LookupElementBuilder.create(name).withTypeText(typeText).withIcon(icon);
        return bold ? item.bold() : item;
    }

    /**
     * Item of an own function, method or class: the lookup object is the definition, so that Ctrl+Q in the list
     * shows its documentation (AhkDocumentationProvider.getDocumentationElementForLookupItem).
     */
    private static LookupElementBuilder item(PsiElement definition, String name, String typeText, @Nullable Icon icon) {
        return LookupElementBuilder.create(definition, name).withTypeText(typeText).withIcon(icon);
    }

    /** Start of a statement: start of the file or line, or directly after else, try or a hotkey. */
    private static boolean isStatementStart(@Nullable IElementType prevType) {
        return prevType == null || prevType == AhkTypes.CRLF || prevType == AhkTypes.L_BRACE
                || prevType == AhkTypes.KEYWORD_ELSE || prevType == AhkTypes.KEYWORD_TRY
                || prevType == AhkTypes.HOTKEY || prevType == AhkTypes.HOTSTRING;
    }

    /** Previous leaf without whitespace and comments (CRLF counts, it is a real token). */
    private static @Nullable PsiElement prevCodeLeaf(PsiElement leaf) {
        PsiElement prev = PsiTreeUtil.prevLeaf(leaf);
        while (prev != null && (prev instanceof com.intellij.psi.PsiWhiteSpace
                || AhkTokenSets.COMMENTS.contains(prev.getNode().getElementType())
                || prev.getTextLength() == 0)) {
            prev = PsiTreeUtil.prevLeaf(prev);
        }
        return prev;
    }

    /** The identifier directly follows a dot. */
    private static boolean isAfterDot(PsiElement identifier) {
        PsiElement prev = PsiTreeUtil.skipWhitespacesAndCommentsBackward(identifier);
        return prev != null && prev.getNode().getElementType() == AhkTypes.DOT;
    }

    /** Lookup key: the name in lower case. */
    private static String key(String name) {
        return name.toLowerCase(Locale.ROOT);
    }
}
