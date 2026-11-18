package de.kumpewo.autohotkey.annotation;

import de.kumpewo.autohotkey.AhkBundle;
import com.intellij.lang.annotation.AnnotationHolder;
import com.intellij.lang.annotation.Annotator;
import com.intellij.lang.annotation.HighlightSeverity;
import com.intellij.openapi.editor.colors.TextAttributesKey;
import com.intellij.openapi.util.TextRange;
import com.intellij.psi.PsiElement;
import com.intellij.psi.tree.IElementType;
import com.intellij.psi.util.PsiTreeUtil;
import de.kumpewo.autohotkey.highlighting.AhkColors;
import de.kumpewo.autohotkey.highlighting.AhkNames;
import de.kumpewo.autohotkey.psi.AhkClass;
import de.kumpewo.autohotkey.psi.AhkDefinitions;
import de.kumpewo.autohotkey.psi.AhkFunc;
import de.kumpewo.autohotkey.psi.AhkLabel;
import de.kumpewo.autohotkey.psi.AhkOtherVersionCode;
import de.kumpewo.autohotkey.psi.AhkTypes;
import de.kumpewo.autohotkey.types.AhkTypeDeclarations;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.HashMap;
import java.util.Map;
import java.util.Set;

/**
 * Highlighting based on the PSI tree (the lexer highlighting in AhkSyntaxHighlighter only knows strings, numbers,
 * comments, hotkeys and hotstrings).
 * <ul>
 *   <li>Keywords: by their token type (KEYWORD_COLORS), everywhere; after a dot (obj.if) an error.</li>
 *   <li>Escape sequences in strings (`n, `", ...).</li>
 *   <li>Directives: token DIRECTIVE_NAME, list directives.txt; unknown ones get a warning.</li>
 *   <li>Identifiers: by their role (see {@link #identifierColor}) from the lists in AhkNames.</li>
 * </ul>
 * The role functions ({@link #isName}, {@link #keywordColor}, {@link #identifierColor}) are public because the
 * case adjustment of the formatter uses the same rules.
 */
public class AhkAnnotator implements Annotator {


    /** Characters that form an escape sequence after the backtick (help: docs/misc/escapechar.htm). */
    private static final String ESCAPE_CHARS = "`;:{nrbtsvaf\"'";

    /**
     * Tables in a separate class (holder idiom): extensions (plugin.xml) must not use static initialization
     * (DevKit inspection, startup time). The JVM loads Tables lazily and thread-safely on first access; an
     * "if (map.isEmpty()) initialize()" would not be thread-safe (annotators run in parallel).
     */
    private static final class Tables {
        /** Keyword tokens and their color. No name lists are needed for them, the lexer knows them. */
        static final Map<IElementType, TextAttributesKey> KEYWORD_COLORS = new HashMap<>();

        /** Parent types under which an identifier is not a free-standing name (definitions, keys, name parts). */
        static final Set<IElementType> NOT_FREE_STANDING = Set.of(
                AhkTypes.MEMBER_EXPR, AhkTypes.PROPERTY, AhkTypes.CLASS_PROPERTY, AhkTypes.ACCESSOR,
                AhkTypes.DYN_REF_EXPR, AhkTypes.FUNC, AhkTypes.LABEL, AhkTypes.CASE_CLAUSE);

        static {
            for (IElementType t : new IElementType[]{
                    AhkTypes.KEYWORD_IF, AhkTypes.KEYWORD_ELSE, AhkTypes.KEYWORD_WHILE, AhkTypes.KEYWORD_LOOP,
                    AhkTypes.KEYWORD_FOR, AhkTypes.KEYWORD_UNTIL, AhkTypes.KEYWORD_RETURN, AhkTypes.KEYWORD_BREAK,
                    AhkTypes.KEYWORD_CONTINUE, AhkTypes.KEYWORD_TRY, AhkTypes.KEYWORD_CATCH, AhkTypes.KEYWORD_FINALLY,
                    AhkTypes.KEYWORD_THROW, AhkTypes.KEYWORD_SWITCH, AhkTypes.KEYWORD_CASE, AhkTypes.KEYWORD_GOTO}) {
                KEYWORD_COLORS.put(t, AhkColors.AHK_FLOWCONTROL);
            }
            for (IElementType t : new IElementType[]{
                    AhkTypes.KEYWORD_AS, AhkTypes.KEYWORD_AND, AhkTypes.KEYWORD_OR, AhkTypes.KEYWORD_NOT,
                    AhkTypes.KEYWORD_IS, AhkTypes.KEYWORD_IN, AhkTypes.KEYWORD_CONTAINS, AhkTypes.KEYWORD_DECL,
                    AhkTypes.KEYWORD_CLASS, AhkTypes.KEYWORD_EXTENDS, AhkTypes.KEYWORD_STRUCT,
                    AhkTypes.KEYWORD_EXPORT, AhkTypes.KEYWORD_UNION}) {
                KEYWORD_COLORS.put(t, AhkColors.AHK_RESERVED);
            }
            // true, false, unset, this, super
            KEYWORD_COLORS.put(AhkTypes.KEYWORD_LITERAL, AhkColors.AHK_RESERVED_VALUE);
        }
    }

    /** Colors one element (called by the platform for every PSI element). */
    @Override
    public void annotate(@NotNull PsiElement element, @NotNull AnnotationHolder holder) {
        IElementType type = element.getNode().getElementType();

        // Line after ";@ignore-next-line": colored uniformly (AhkIgnoredLineAnnotator), no other colors
        if (AhkIgnoredLines.isIgnored(element)) return;

        // Label "Name:": name and colon in the label color, even if the name would otherwise have its own color
        if (element instanceof AhkLabel) {
            color(holder, element, AhkColors.AHK_LABEL);
            return;
        }
        if (type == AhkTypes.IDENTIFIER && element.getParent() instanceof AhkLabel) return;

        if (type == AhkTypes.DIRECTIVE_NAME) {
            annotateDirective(element, holder);
            return;
        }

        if (isName(element)) {
            TextAttributesKey key = identifierColor(element);
            if (key != null) color(holder, element, key);
            return;
        }
        TextAttributesKey keywordColor = Tables.KEYWORD_COLORS.get(type);
        if (keywordColor != null) {
            // The grammar allows keywords after the dot (obj.if); it is shown as an error
            if (isAfterDot(element)) {
                holder.newAnnotation(HighlightSeverity.ERROR, AhkBundle.message("annotator.keyword.as.member"))
                        .range(element)
                        .create();
            } else {
                color(holder, element, keywordColor);
            }
            return;
        }

        if (type == AhkTypes.STRING || type == AhkTypes.UNCLOSED_STRING || type == AhkTypes.HOTSTRING_TEXT) {
            annotateEscapes(element, holder);
        }

        // No errors in a file for another AHK version (OTHER_VERSION_CODE); only the warning at #Requires applies there
        if (type == AhkTypes.UNCLOSED_STRING && !(element.getParent() instanceof AhkOtherVersionCode)) {
            holder.newAnnotation(HighlightSeverity.ERROR, AhkBundle.message("annotator.unclosed.string"))
                    .range(element)
                    .create();
            return;
        }

        if (element instanceof AhkClass) {
            PsiElement identifier = ((AhkClass) element).getNameIdentifier();
            if (identifier != null) color(holder, identifier, AhkColors.AHK_CLASS);
            return;
        }
        if (element instanceof AhkFunc) {
            PsiElement identifier = ((AhkFunc) element).getNameIdentifier();
            // a private method gets the private color through identifierColor instead
            if (identifier != null && !isPrivateMemberDefinition(identifier)) color(holder, identifier, AhkColors.AHK_FUNC);
        }
    }

    /**
     * Leaf that is colored as a name by its role ({@link #identifierColor}): an identifier, or "Class" used as a
     * normal identifier (Class.Prototype); otherwise "class" is a keyword.
     */
    public static boolean isName(@NotNull PsiElement leaf) {
        IElementType type = leaf.getNode().getElementType();
        return type == AhkTypes.IDENTIFIER
                || type == AhkTypes.KEYWORD_CLASS && leaf.getParent().getNode().getElementType() == AhkTypes.REF_EXPR;
    }

    /**
     * Color of a keyword token; null for other tokens and after the dot (obj.if, an error there).
     * For leaves where {@link #isName} applies, {@link #identifierColor} is used instead.
     */
    public static @Nullable TextAttributesKey keywordColor(@NotNull PsiElement leaf) {
        TextAttributesKey key = Tables.KEYWORD_COLORS.get(leaf.getNode().getElementType());
        return key == null || isAfterDot(leaf) ? null : key;
    }

    /** Colors a known directive, warns about an unknown one. */
    private static void annotateDirective(PsiElement element, AnnotationHolder holder) {
        TextAttributesKey key = AhkNames.of(element).directive(element.getText());
        if (key != null) {
            color(holder, element, key);
        } else if (PsiTreeUtil.getParentOfType(element, AhkOtherVersionCode.class) == null
                && !"#Import".equalsIgnoreCase(element.getText()) && !"#Module".equalsIgnoreCase(element.getText())) {
            // No warning in files for another version (#Requires AutoHotkey v1.1); other directives apply there.
            // #Import/#Module outside v2.1: error "requires AutoHotkey v2.1" from AhkModuleAnnotator instead.
            holder.newAnnotation(HighlightSeverity.WARNING, AhkBundle.message("annotator.unknown.directive"))
                    .range(element)
                    .create();
        }
    }

    /**
     * Color of an identifier by its role, null = no special color:
     * <ol>
     *   <li>"default" as a branch of a switch: flow control</li>
     *   <li>directly after "Loop": Files/Parse/Read/Reg as flow control</li>
     *   <li>name after a dot (obj.Name): methods/properties (methods.txt, properties.txt); a name declared with
     *       ;@declare-private gets AHK_PRIVATE_MEMBER (before the built-in color only if declared explicitly)</li>
     *   <li>member definitions in a class (method, property, field): AHK_PRIVATE_MEMBER if declared private</li>
     *   <li>free-standing name (variable, call, command MsgBox "x", extends, parameter, declaration, for variable
     *       ...): variables/classes/functions (variables.txt, classes.txt, functions.txt); a name declared with
     *       ;@declare-type gets AHK_DECLARED_TYPE (built-in names only with an explicit declaration, not via a
     *       wildcard; extends names never)</li>
     * </ol>
     * v2.1 modules: a built-in function, class or variable name that the own module defines or imports
     * explicitly is not colored as built-in (a module may hide built-ins); after {@code AHK.} (the built-in module)
     * names get the color of the built-in name, after another module no built-in member color.
     * Not free-standing (NOT_FREE_STANDING): function, class and label names, properties in classes, object keys,
     * parts of dynamic names. Methods/properties (Length) are never colored when free-standing.
     */
    public static @Nullable TextAttributesKey identifierColor(@NotNull PsiElement identifier) {
        String name = identifier.getText();
        AhkNames names = AhkNames.of(identifier);
        PsiElement parent = identifier.getParent();
        IElementType parentType = parent.getNode().getElementType();

        if (parentType == AhkTypes.CASE_CLAUSE) return AhkColors.AHK_FLOWCONTROL;   // only "default" (grammar)
        if (isLoopSubcommand(identifier)) return AhkColors.AHK_FLOWCONTROL;

        if (parentType == AhkTypes.MEMBER_EXPR) {
            if (!isMemberName(identifier)) return null;
            de.kumpewo.autohotkey.module.AhkModules.Module module = moduleLeftOfDot(parent);
            if (module != null) return module.isBuiltin() ? names.nonMember(name) : null;
            // ;@declare-private: explicit beats built-in, via wildcard only without a built-in color
            AhkTypeDeclarations.Privacy privacy = AhkTypeDeclarations.privacy(identifier.getContainingFile(), name);
            if (privacy == AhkTypeDeclarations.Privacy.EXPLICIT) return AhkColors.AHK_PRIVATE_MEMBER;
            // Gui.Button: nested class if exactly the outer class is on the left of the dot
            PsiElement qualifier = parent.getFirstChild();
            if (qualifier != null && qualifier.getNode().getElementType() == AhkTypes.REF_EXPR) {
                TextAttributesKey nested = names.nestedClass(qualifier.getText(), name);
                if (nested != null) return nested;
            }
            TextAttributesKey member = names.member(name);
            if (member != null) return member;
            return privacy == AhkTypeDeclarations.Privacy.WILDCARD ? AhkColors.AHK_PRIVATE_MEMBER : null;
        }
        // Definition of a method or property in a class: private color, otherwise none here (methods: AHK_FUNC)
        if (parentType == AhkTypes.FUNC || parentType == AhkTypes.CLASS_PROPERTY) {
            return isPrivateMemberDefinition(identifier) ? AhkColors.AHK_PRIVATE_MEMBER : null;
        }
        if (Tables.NOT_FREE_STANDING.contains(parentType)) return null;
        // Field in a class body (count := 0, static count := 0): a member, private before/after built-in like above
        if (isFieldName(identifier)) {
            AhkTypeDeclarations.Privacy privacy = AhkTypeDeclarations.privacy(identifier.getContainingFile(), name);
            if (privacy == AhkTypeDeclarations.Privacy.EXPLICIT) return AhkColors.AHK_PRIVATE_MEMBER;
            TextAttributesKey builtin = names.nonMember(name);
            if (builtin != null) return builtin;
            return privacy == AhkTypeDeclarations.Privacy.WILDCARD ? AhkColors.AHK_PRIVATE_MEMBER : null;
        }
        if (parentType == AhkTypes.CLASS) {
            if (isExtendsName(identifier)) return names.nonMember(name);   // base class, not a variable
            // "extends Gui.Edit": part after the dot, nested class of the class before it
            PsiElement dot = PsiTreeUtil.skipWhitespacesAndCommentsBackward(identifier);
            if (dot != null && dot.getNode().getElementType() == AhkTypes.DOT) {
                PsiElement outer = PsiTreeUtil.skipWhitespacesAndCommentsBackward(dot);
                return outer != null ? names.nestedClass(outer.getText(), name) : null;
            }
            // The class name itself: color AHK_CLASS via the element AhkClass
            return null;
        }
        // Type declaration (;@declare-type, @param {Type} of the enclosing function): explicitly declared beats
        // built-in, via wildcard only without built-in
        TextAttributesKey builtin = names.nonMember(name);
        if (builtin != null && builtin != AhkColors.AHK_BUILTIN_VARIABLE && hiddenByModule(identifier, name)) builtin = null;
        AhkTypeDeclarations.Declared declared = AhkTypeDeclarations.lookup(identifier, name);
        if (declared != null && (declared.explicit() || builtin == null)) return AhkColors.AHK_DECLARED_TYPE;
        return builtin;
    }

    /**
     * v2.1: the module of the free-standing name defines or imports a function, class or module of this name, so the
     * built-in one is hidden (built-in variables cannot be reused, their color stays).
     */
    private static boolean hiddenByModule(@NotNull PsiElement identifier, @NotNull String name) {
        com.intellij.psi.PsiFile file = identifier.getContainingFile();
        if (!de.kumpewo.autohotkey.module.AhkModules.isActive(file)) return false;
        return de.kumpewo.autohotkey.module.AhkModules.ownNames(de.kumpewo.autohotkey.module.AhkModules.moduleOf(identifier))
                .contains(name.toLowerCase(java.util.Locale.ROOT));
    }

    /** v2.1: the module left of the dot of a member access ({@code X.Name}, {@code AHK.Name}), or {@code null}. */
    private static de.kumpewo.autohotkey.module.AhkModules.@Nullable Module moduleLeftOfDot(@NotNull PsiElement memberExpr) {
        com.intellij.psi.PsiFile file = memberExpr.getContainingFile();
        if (!de.kumpewo.autohotkey.module.AhkModules.isActive(file) || !de.kumpewo.autohotkey.module.AhkModules.usesModules(file)) {
            return null;
        }
        PsiElement qualifier = memberExpr.getFirstChild();
        return qualifier == null ? null : de.kumpewo.autohotkey.module.AhkModules.qualifierModule(qualifier,
                n -> de.kumpewo.autohotkey.navigation.AhkDefinitionReference.finder(
                        de.kumpewo.autohotkey.navigation.AhkDefinitionReference.Kind.FREE, n, null));
    }

    /**
     * Colors escape sequences (backtick + character from ESCAPE_CHARS) in a string or the replacement text of a
     * hotstring; unknown ones stay uncolored.
     */
    private static void annotateEscapes(PsiElement string, AnnotationHolder holder) {
        String text = string.getText();
        int start = string.getTextRange().getStartOffset();
        for (int i = 0; i + 1 < text.length(); i++) {
            if (text.charAt(i) != '`') continue;
            if (ESCAPE_CHARS.indexOf(Character.toLowerCase(text.charAt(i + 1))) >= 0) {
                holder.newSilentAnnotation(HighlightSeverity.INFORMATION)
                        .range(TextRange.from(start + i, 2))
                        .textAttributes(AhkColors.AHK_STRING_ESCAPE)
                        .create();
            }
            i++;   // the character after the backtick belongs to the sequence (`` is a backtick)
        }
    }

    /**
     * Name of a method ({@code Name() {}} directly in a class) or a property ({@code Name { get ... }},
     * {@code Name => ...}) that is declared private with {@code ;@declare-private} (explicitly or via a wildcard).
     */
    static boolean isPrivateMemberDefinition(@NotNull PsiElement identifier) {
        PsiElement definition = identifier.getParent();
        if (definition instanceof AhkFunc func && AhkDefinitions.ownerClass(func) == null) return false;   // function
        if (!(definition instanceof AhkFunc) && definition.getNode().getElementType() != AhkTypes.CLASS_PROPERTY) {
            return false;
        }
        return AhkTypeDeclarations.privacy(identifier.getContainingFile(), identifier.getText())
                != AhkTypeDeclarations.Privacy.NONE;
    }

    /** Name of a field in a class body ({@code count := 0}, {@code static count := 0}; {@link AhkDefinitions#fields}). */
    static boolean isFieldName(@NotNull PsiElement identifier) {
        return AhkDefinitions.isFieldName(identifier);
    }

    /** "Loop Files ...", "Loop Parse ...": the identifier is the first word after "Loop". */
    private static boolean isLoopSubcommand(PsiElement identifier) {
        if (!AhkNames.isLoopSubcommand(identifier.getText())) return false;
        PsiElement prev = PsiTreeUtil.prevVisibleLeaf(identifier);
        return prev != null && prev.getNode().getElementType() == AhkTypes.KEYWORD_LOOP;
    }

    /** The whole name after the dot: not for mixed forms like obj.prefix%part% (dynamic name). */
    private static boolean isMemberName(PsiElement identifier) {
        if (!isAfterDot(identifier)) return false;
        PsiElement next = identifier.getNextSibling();   // directly adjacent, without whitespace
        return next == null || next.getNode().getElementType() != AhkTypes.DEREF_EXPR
                && next.getNode().getElementType() != AhkTypes.IDENTIFIER
                && next.getNode().getElementType() != AhkTypes.NUMBER;
    }

    /** First part of the name after the dot of a member access (obj.name). */
    private static boolean isAfterDot(PsiElement element) {
        if (element.getParent().getNode().getElementType() != AhkTypes.MEMBER_EXPR) return false;
        PsiElement prev = PsiTreeUtil.skipWhitespacesAndCommentsBackward(element);
        return prev != null && prev.getNode().getElementType() == AhkTypes.DOT;
    }

    /** "class X extends Name": the name of the base class. */
    private static boolean isExtendsName(PsiElement identifier) {
        if (!(identifier.getParent() instanceof AhkClass)) return false;
        PsiElement prev = PsiTreeUtil.skipWhitespacesAndCommentsBackward(identifier);
        return prev != null && prev.getNode().getElementType() == AhkTypes.KEYWORD_EXTENDS;
    }

    /** Silent annotation that only sets the color. */
    private static void color(AnnotationHolder holder, PsiElement element, TextAttributesKey key) {
        holder.newSilentAnnotation(HighlightSeverity.INFORMATION)
                .range(element)
                .textAttributes(key)
                .create();
    }
}
