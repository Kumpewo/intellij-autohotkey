package de.kumpewo.autohotkey.parser;

import de.kumpewo.autohotkey.AhkBundle;
import com.intellij.lang.PsiBuilder;
import com.intellij.lang.parser.GeneratedParserUtilBase;
import com.intellij.openapi.util.Key;
import com.intellij.psi.TokenType;
import com.intellij.psi.tree.IElementType;
import de.kumpewo.autohotkey.psi.AhkTypes;
import org.jetbrains.annotations.Nullable;

/**
 * External predicates and rules of the grammar ({@code Ahk.bnf}, {@code parserUtilClass}): whitespace checks,
 * the state inside {@code %…%} and {@code ( )}, and error markers that let the parser continue.
 * The PsiBuilder skips whitespace only on getTokenType(); rawLookup(-1) still sees it afterwards.
 */
public class AhkParserUtil extends GeneratedParserUtilBase {

    private static final Key<Boolean> IN_DEREF = Key.create("ahk.inDeref");
    private static final Key<Integer> PAREN_DEPTH = Key.create("ahk.parenDepth");
    private static final Key<AhkLanguageLevel> LEVEL = Key.create("ahk.languageLevel");
    /** Number of brackets ( ) [ ] { } of expressions around the current position ({@link #enclosed}). */
    private static final Key<Integer> ENCLOSURE_DEPTH = Key.create("ahk.enclosureDepth");
    /** Inside the head of a control statement: the enclosure depth at the start of the head ({@link #controlHead}). */
    private static final Key<Integer> HEAD_DEPTH = Key.create("ahk.headDepth");

    /** No whitespace directly before the current token: foo(x), a[1], a%b%. */
    public static boolean adjacent(PsiBuilder b, int level) {
        b.getTokenType();   // make sure whitespace is skipped before looking back "raw"
        return !isGap(b.rawLookup(-1));
    }

    /**
     * Whitespace, but no line break, before the current token: required for implicit concatenation.
     * (Line breaks are real CRLF tokens anyway; they are part of whitespace only in continuation lines.)
     */
    public static boolean spacedWithoutLineBreak(PsiBuilder b, int level) {
        b.getTokenType();
        if (!isGap(b.rawLookup(-1))) return false;
        int from = b.rawTokenTypeStart(-1);
        int to = b.rawTokenTypeStart(0);
        if (from < 0 || to < from) return false;
        // A line break in the whitespace before "(" only comes from a continuation section (Ahk.flex, CS_OPEN_LINE):
        // the section continues the previous line ("MsgBox<line break>(<line break>"text"<line break>)").
        if (b.getTokenType() == AhkTypes.L_PAREN) return true;
        CharSequence gap = b.getOriginalText().subSequence(from, to);
        for (int i = 0; i < gap.length(); i++) {
            char c = gap.charAt(i);
            if (c == '\n' || c == '\r') return false;
        }
        return true;
    }

    /** Parses the content of %...% in a state where a "%" cannot be an operand (it is the end then). */
    public static boolean inDeref(PsiBuilder b, int level, Parser parser) {
        Boolean old = b.getUserData(IN_DEREF);
        b.putUserData(IN_DEREF, Boolean.TRUE);
        try {
            return parser.parse(b, level);
        } finally {
            b.putUserData(IN_DEREF, old);
        }
    }

    /**
     * Parses the content of ( ) in a state where concatenation is possible across line breaks. Like
     * {@link #enclosed}, the content counts as enclosed in brackets.
     */
    public static boolean inParens(PsiBuilder b, int level, Parser parser) {
        Integer old = b.getUserData(PAREN_DEPTH);
        b.putUserData(PAREN_DEPTH, (old == null ? 0 : old) + 1);
        try {
            return enclosed(b, level, parser);
        } finally {
            b.putUserData(PAREN_DEPTH, old);
        }
    }

    /**
     * Parses the content of brackets ( ) [ ] { } of an expression: there a function definition expression may have
     * its "{" on a following line and may be part of the head of a control statement ({@link #funcExprStart}).
     */
    public static boolean enclosed(PsiBuilder b, int level, Parser parser) {
        Integer old = b.getUserData(ENCLOSURE_DEPTH);
        b.putUserData(ENCLOSURE_DEPTH, depth(old) + 1);
        try {
            return parser.parse(b, level);
        } finally {
            b.putUserData(ENCLOSURE_DEPTH, old);
        }
    }

    /**
     * Parses the head of a control statement (if, while, loop, for, switch, catch, until): there a function definition
     * expression is only allowed inside brackets; {@code if f() {} } is a call followed by the block of the if.
     */
    public static boolean controlHead(PsiBuilder b, int level, Parser parser) {
        Integer old = b.getUserData(HEAD_DEPTH);
        b.putUserData(HEAD_DEPTH, depth(b.getUserData(ENCLOSURE_DEPTH)));
        try {
            return parser.parse(b, level);
        } finally {
            b.putUserData(HEAD_DEPTH, old);
        }
    }

    /**
     * A function definition expression starts here (v2.1, {@code Name(params) { ... }} or {@code (params) { ... }}):
     * checked by lookahead only, so that a failing attempt costs nothing (otherwise every call would parse its
     * arguments twice, nested calls exponentially often). Optionally a name directly before "(", then the matching ")"
     * and "{" on the same line; inside brackets also line breaks before "{". In the head of a control statement only
     * inside brackets opened after the start of the head. Whether the parameter list is valid is left to the grammar.
     * Raw lookup (constant time per token); the search for ")" ends at the "}" of the enclosing block.
     */
    public static boolean funcExprStart(PsiBuilder b, int level) {
        Integer head = b.getUserData(HEAD_DEPTH);
        int enclosure = depth(b.getUserData(ENCLOSURE_DEPTH));
        if (head != null && enclosure <= head) return false;
        IElementType first = b.getTokenType();   // skips whitespace before the current token
        int i = 0;
        if (first == AhkTypes.IDENTIFIER) i = 1;
        if (b.rawLookup(i) != AhkTypes.L_PAREN) return false;   // the name must be directly before "("
        int parens = 0;
        int braces = 0;
        for (; ; i++) {
            IElementType t = b.rawLookup(i);
            if (t == null) return false;
            if (t == AhkTypes.L_PAREN) parens++;
            if (t == AhkTypes.R_PAREN && --parens == 0) break;
            if (t == AhkTypes.L_BRACE) braces++;
            if (t == AhkTypes.R_BRACE && --braces < 0) return false;
        }
        for (i++; ; i++) {
            IElementType t = b.rawLookup(i);
            if (t == AhkTypes.L_BRACE) return true;
            if (t == AhkTypes.CRLF && enclosure > 0) continue;
            if (t != TokenType.WHITE_SPACE && !isGap(t) && t != AhkTypes.LINE_COMMENT) return false;
        }
    }

    /**
     * Parses the body of a function definition expression in a fresh state: its statements are not inside the
     * brackets, the deref or the control statement head around the expression.
     */
    public static boolean funcBody(PsiBuilder b, int level, Parser parser) {
        Boolean deref = b.getUserData(IN_DEREF);
        Integer parens = b.getUserData(PAREN_DEPTH);
        Integer enclosure = b.getUserData(ENCLOSURE_DEPTH);
        Integer head = b.getUserData(HEAD_DEPTH);
        b.putUserData(IN_DEREF, null);
        b.putUserData(PAREN_DEPTH, null);
        b.putUserData(ENCLOSURE_DEPTH, null);
        b.putUserData(HEAD_DEPTH, null);
        try {
            return parser.parse(b, level);
        } finally {
            b.putUserData(IN_DEREF, deref);
            b.putUserData(PAREN_DEPTH, parens);
            b.putUserData(ENCLOSURE_DEPTH, enclosure);
            b.putUserData(HEAD_DEPTH, head);
        }
    }

    /** A depth stored as user data ({@code null} = 0). */
    private static int depth(@Nullable Integer value) {
        return value == null ? 0 : value;
    }

    /** The parser is currently inside ( ). */
    public static boolean inParensNow(PsiBuilder b, int level) {
        Integer depth = b.getUserData(PAREN_DEPTH);
        return depth != null && depth > 0;
    }

    /** The current token is a "%" that may act as an operand (start of a deref): not inside %...%. */
    public static boolean percentOperand(PsiBuilder b, int level) {
        return b.getTokenType() == AhkTypes.PERCENT && b.getUserData(IN_DEREF) == null;
    }

    /** The current token is the name "get" or "set" (not a keyword, only a name with a special meaning). */
    public static boolean accessorName(PsiBuilder b, int level) {
        if (b.getTokenType() != AhkTypes.IDENTIFIER) return false;
        String text = b.getTokenText();
        return "get".equalsIgnoreCase(text) || "set".equalsIgnoreCase(text);
    }

    /** Parses a statement in a switch outside of any case and marks it as an error. */
    public static boolean strayInSwitch(PsiBuilder b, int level, Parser parser) {
        PsiBuilder.Marker m = b.mark();
        boolean r = parser.parse(b, level);
        if (r) {
            m.error(AhkBundle.message("parser.case.or.default.expected"));
        } else {
            m.rollbackTo();
        }
        return r;
    }

    /** Parses a surplus "}" at file level and marks it as an error, so that the rest of the file is still parsed. */
    public static boolean strayBrace(PsiBuilder b, int level, Parser parser) {
        PsiBuilder.Marker m = b.mark();
        boolean r = parser.parse(b, level);
        if (r) {
            m.error(AhkBundle.message("parser.stray.brace"));
        } else {
            m.rollbackTo();
        }
        return r;
    }

    /**
     * Determines the language level of the file at its start ({@link #languageLevel}): the first
     * "#Requires AutoHotkey ..." with a version ({@link AhkVersion#level}), otherwise the default from the settings.
     * If that directive does NOT require at least version 2, the whole file is swallowed unparsed (element
     * OTHER_VERSION_CODE, warning in AhkVersionAnnotator). Comments are separate tokens and therefore don't count.
     */
    public static boolean otherVersion(PsiBuilder b, int level) {
        b.getTokenType();
        AhkLanguageLevel found = null;
        CharSequence text = b.getOriginalText();
        for (int i = 0; b.rawLookup(i) != null && found == null; i++) {
            if (b.rawLookup(i) != AhkTypes.DIRECTIVE_NAME || !"#Requires".equalsIgnoreCase(
                    text.subSequence(b.rawTokenTypeStart(i), b.rawTokenTypeStart(i + 1)).toString())) {
                continue;
            }
            int j = i + 1;
            while (b.rawLookup(j) == TokenType.WHITE_SPACE) j++;
            if (b.rawLookup(j) != AhkTypes.DIRECTIVE_ARGS) continue;
            found = AhkVersion.level(text.subSequence(b.rawTokenTypeStart(j), b.rawTokenTypeStart(j + 1)));
        }
        b.putUserData(LEVEL, found != null ? found : de.kumpewo.autohotkey.settings.AhkSettings.defaultLevel());
        if (found != AhkLanguageLevel.OTHER) return false;
        while (!b.eof()) b.advanceLexer();
        return true;
    }

    /**
     * Language level of the file being parsed (determined by {@link #otherVersion} at the start of the file; the
     * default from the settings if the grammar did not call it).
     */
    public static AhkLanguageLevel languageLevel(PsiBuilder b) {
        AhkLanguageLevel found = b.getUserData(LEVEL);
        return found != null ? found : de.kumpewo.autohotkey.settings.AhkSettings.defaultLevel();
    }

    /**
     * Parses a construct of AutoHotkey v2.1. In a file of another language level it is still parsed (so that the
     * rest of the expression stays intact), but marked as an error "requires AutoHotkey v2.1".
     */
    public static boolean requiresV21(PsiBuilder b, int level, Parser parser) {
        if (languageLevel(b) == AhkLanguageLevel.V2_1) return parser.parse(b, level);
        PsiBuilder.Marker m = b.mark();
        boolean r = parser.parse(b, level);
        if (r) {
            m.error(AhkBundle.message("parser.requires.v21"));
        } else {
            m.rollbackTo();
        }
        return r;
    }

    /** The current token is the maybe-assign operator {@code ??=} (one of the ASSIGN_OP tokens, v2.1). */
    public static boolean maybeAssignOp(PsiBuilder b, int level) {
        return b.getTokenType() == AhkTypes.ASSIGN_OP && "??=".equals(b.getTokenText());
    }

    /**
     * In v2.1 the name "struct" followed by a name starts a struct definition ({@code struct Name { ... }}): the
     * token is remapped to KEYWORD_STRUCT. Remapping is not undone by a rollback, therefore the lookahead first.
     * Everywhere else (and in v2.0) "struct" stays a normal name (the class Struct in v2.1).
     */
    public static boolean structKeyword(PsiBuilder b, int level) {
        if (b.getTokenType() != AhkTypes.IDENTIFIER || !"struct".equalsIgnoreCase(b.getTokenText())
                || b.lookAhead(1) != AhkTypes.IDENTIFIER || languageLevel(b) != AhkLanguageLevel.V2_1) {
            return false;
        }
        b.remapCurrentToken(AhkTypes.KEYWORD_STRUCT);
        return true;
    }

    /**
     * Priority of the Pratt parser below the postfix operators (call, index, member access): {@code expr} with this
     * priority parses an operand with its postfix chain, but no binary operator. It is the index of power_expr in the
     * operator priority table of the generated {@code AhkParser.expr} and must be adapted when the order of the
     * alternatives of {@code expr} in Ahk.bnf changes (a test checks {@code x : Int32 := 1}).
     */
    private static final int POSTFIX_PRIORITY = 17;

    /**
     * Type specifier of a typed property (v2.1): an operand with calls, index and member access, without binary
     * operators, so that {@code := initializer} after the type is not taken as an assignment.
     */
    public static boolean typeExpr(PsiBuilder b, int level) {
        return AhkParser.expr(b, level + 1, POSTFIX_PRIORITY);
    }

    /**
     * The statement contains a typed property (v2.1, {@code Name : Type}): a name followed by ":" at its start or after
     * a comma outside of brackets, before the end of the line. "Name:" at the end of the line is a label. Lookahead
     * only (linear in the length of the line), so that normal statements are not parsed twice.
     */
    public static boolean typedDeclStart(PsiBuilder b, int level) {
        if (b.getTokenType() != AhkTypes.IDENTIFIER) return false;
        if (isTypedProperty(b, 0)) return true;
        int depth = 0;
        for (int i = 1; ; i++) {
            IElementType t = b.lookAhead(i);
            if (t == null) return false;
            if (t == AhkTypes.L_PAREN || t == AhkTypes.L_BRACKET || t == AhkTypes.L_BRACE) {
                depth++;
            } else if (t == AhkTypes.R_PAREN || t == AhkTypes.R_BRACKET || t == AhkTypes.R_BRACE) {
                if (--depth < 0) return false;
            } else if (depth == 0 && t == AhkTypes.CRLF) {
                return false;
            } else if (depth == 0 && t == AhkTypes.COMMA) {
                int j = i + 1;
                while (b.lookAhead(j) == AhkTypes.CRLF) j++;
                if (isTypedProperty(b, j)) return true;
            }
        }
    }

    /** At lookahead position {@code i}: a name, ":" and something else than the end of the line. */
    private static boolean isTypedProperty(PsiBuilder b, int i) {
        IElementType after = b.lookAhead(i + 2);
        return b.lookAhead(i) == AhkTypes.IDENTIFIER && b.lookAhead(i + 1) == AhkTypes.COLON
                && after != null && after != AhkTypes.CRLF;
    }

    /** The current token is the plain assignment ":=" (initializer of a typed property). */
    public static boolean plainAssign(PsiBuilder b, int level) {
        return b.getTokenType() == AhkTypes.ASSIGN_OP && ":=".equals(b.getTokenText());
    }

    /**
     * "union" followed by a block whose first item is a typed property ({@code union { x : Int32 ...}}, v2.1): the
     * token is remapped to KEYWORD_UNION. "union" with another block (also an empty one) is a property of that name.
     * On every language level (AhkTypedPropertyAnnotator reports v2.0). Remapping is not undone by a rollback,
     * therefore the lookahead first.
     */
    public static boolean unionKeyword(PsiBuilder b, int level) {
        if (b.getTokenType() != AhkTypes.IDENTIFIER || !"union".equalsIgnoreCase(b.getTokenText())) return false;
        int i = 1;
        while (b.lookAhead(i) == AhkTypes.CRLF) i++;
        if (b.lookAhead(i) != AhkTypes.L_BRACE) return false;
        i++;
        while (b.lookAhead(i) == AhkTypes.CRLF) i++;
        if (!isTypedProperty(b, i)) return false;
        b.remapCurrentToken(AhkTypes.KEYWORD_UNION);
        return true;
    }

    /** The current token is the directive name "#Import" (v2.1, its arguments are normal tokens, see Ahk.flex). */
    public static boolean importDirective(PsiBuilder b, int level) {
        return b.getTokenType() == AhkTypes.DIRECTIVE_NAME && "#Import".equalsIgnoreCase(b.getTokenText());
    }

    /**
     * After "#Import" the name "Export" followed by a module name (identifier or string) is the keyword Export: the
     * token is remapped to KEYWORD_EXPORT. "#Import Export" alone imports a module named Export. On every language
     * level, like the rest of #Import (remapping is not undone by a rollback, therefore the lookahead first).
     */
    public static boolean exportKeyword(PsiBuilder b, int level) {
        if (b.getTokenType() != AhkTypes.IDENTIFIER || !"export".equalsIgnoreCase(b.getTokenText())) return false;
        IElementType next = b.lookAhead(1);
        if (next != AhkTypes.IDENTIFIER && next != AhkTypes.STRING) return false;
        b.remapCurrentToken(AhkTypes.KEYWORD_EXPORT);
        return true;
    }

    /** The current token is the name "default" (branch of a switch, not a keyword). */
    public static boolean defaultName(PsiBuilder b, int level) {
        return b.getTokenType() == AhkTypes.IDENTIFIER && "default".equalsIgnoreCase(b.getTokenText());
    }

    /**
     * The previous token (ignoring whitespace and block comments on the line) is "}" and the current token starts
     * further code on the same line: a closing brace of a block followed by {@code MsgBox x}.
     */
    public static boolean afterClosingBrace(PsiBuilder b, int level) {
        IElementType current = b.getTokenType();
        if (current == null || current == AhkTypes.CRLF || current == AhkTypes.R_BRACE) return false;
        int i = -1;
        IElementType prev = b.rawLookup(i);
        while (prev != null && isGap(prev)) {
            prev = b.rawLookup(--i);
        }
        return prev == AhkTypes.R_BRACE;
    }

    /**
     * The current token is the reserved word "this": allowed as a parameter name ({@code f(this) { }},
     * {@code (this) => …}, {@code this => …}); it stays a KEYWORD_LITERAL.
     */
    public static boolean thisName(PsiBuilder b, int level) {
        return b.getTokenType() == AhkTypes.KEYWORD_LITERAL && "this".equalsIgnoreCase(b.getTokenText());
    }

    /**
     * The current token starts a named fat arrow function in an expression ({@code Sum(a, b) => a + b}): a name, a
     * directly following "(", the matching ")" and then "=>". Checked by a linear scan of the raw tokens, so that an
     * ordinary call is not parsed twice.
     */
    public static boolean arrowFuncStart(PsiBuilder b, int level) {
        if (b.getTokenType() != AhkTypes.IDENTIFIER || b.rawLookup(1) != AhkTypes.L_PAREN) return false;
        int depth = 0;
        for (int i = 1; ; i++) {
            IElementType t = b.rawLookup(i);
            if (t == null) return false;
            if (t == AhkTypes.L_PAREN || t == AhkTypes.L_BRACKET || t == AhkTypes.L_BRACE) {
                depth++;
            } else if (t == AhkTypes.R_PAREN || t == AhkTypes.R_BRACKET || t == AhkTypes.R_BRACE) {
                if (--depth > 0) continue;
                int j = i + 1;
                while (isGap(b.rawLookup(j))) j++;
                return b.rawLookup(j) == AhkTypes.FAT_ARROW;
            }
        }
    }

    /** Whitespace or a block comment between two tokens. */
    private static boolean isGap(IElementType t) {
        return t == TokenType.WHITE_SPACE || t == AhkTypes.BLOCK_COMMENT || t == AhkTypes.BAD_COMMENT;
    }
}
