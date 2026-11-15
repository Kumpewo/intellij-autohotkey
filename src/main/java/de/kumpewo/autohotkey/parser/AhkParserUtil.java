package de.kumpewo.autohotkey.parser;

import de.kumpewo.autohotkey.AhkBundle;
import com.intellij.lang.PsiBuilder;
import com.intellij.lang.parser.GeneratedParserUtilBase;
import com.intellij.openapi.util.Key;
import com.intellij.psi.TokenType;
import com.intellij.psi.tree.IElementType;
import de.kumpewo.autohotkey.psi.AhkTypes;

/**
 * External predicates and rules of the grammar ({@code Ahk.bnf}, {@code parserUtilClass}): whitespace checks,
 * the state inside {@code %…%} and {@code ( )}, and error markers that let the parser continue.
 * The PsiBuilder skips whitespace only on getTokenType(); rawLookup(-1) still sees it afterwards.
 */
public class AhkParserUtil extends GeneratedParserUtilBase {

    private static final Key<Boolean> IN_DEREF = Key.create("ahk.inDeref");
    private static final Key<Integer> PAREN_DEPTH = Key.create("ahk.parenDepth");

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

    /** Parses the content of ( ) in a state where concatenation is possible across line breaks. */
    public static boolean inParens(PsiBuilder b, int level, Parser parser) {
        Integer old = b.getUserData(PAREN_DEPTH);
        b.putUserData(PAREN_DEPTH, (old == null ? 0 : old) + 1);
        try {
            return parser.parse(b, level);
        } finally {
            b.putUserData(PAREN_DEPTH, old);
        }
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
     * If the first "#Requires AutoHotkey ..." with a version does NOT require at least version 2 (AhkVersion),
     * the whole file is swallowed unparsed (element OTHER_VERSION_CODE, warning in AhkVersionAnnotator).
     * Without such a directive the file is parsed normally. Comments are separate tokens and therefore don't count.
     */
    public static boolean otherVersion(PsiBuilder b, int level) {
        b.getTokenType();
        CharSequence text = b.getOriginalText();
        for (int i = 0; b.rawLookup(i) != null; i++) {
            if (b.rawLookup(i) != AhkTypes.DIRECTIVE_NAME || !"#Requires".equalsIgnoreCase(
                    text.subSequence(b.rawTokenTypeStart(i), b.rawTokenTypeStart(i + 1)).toString())) {
                continue;
            }
            int j = i + 1;
            while (b.rawLookup(j) == TokenType.WHITE_SPACE) j++;
            if (b.rawLookup(j) != AhkTypes.DIRECTIVE_ARGS) continue;
            Boolean v2 = AhkVersion.requiresV2(text.subSequence(b.rawTokenTypeStart(j), b.rawTokenTypeStart(j + 1)));
            if (v2 == null) continue;
            if (v2) return false;
            while (!b.eof()) b.advanceLexer();
            return true;
        }
        return false;
    }

    /** The current token is the name "default" (branch of a switch, not a keyword). */
    public static boolean defaultName(PsiBuilder b, int level) {
        return b.getTokenType() == AhkTypes.IDENTIFIER && "default".equalsIgnoreCase(b.getTokenText());
    }

    /** Whitespace or a block comment between two tokens. */
    private static boolean isGap(IElementType t) {
        return t == TokenType.WHITE_SPACE || t == AhkTypes.BLOCK_COMMENT || t == AhkTypes.BAD_COMMENT;
    }
}
