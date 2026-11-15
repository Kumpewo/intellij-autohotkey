package de.kumpewo.autohotkey.highlighting;

import com.intellij.codeInsight.highlighting.PairedBraceMatcherAdapter;
import com.intellij.lang.BracePair;
import com.intellij.lang.PairedBraceMatcher;
import com.intellij.openapi.editor.highlighter.HighlighterIterator;
import com.intellij.openapi.fileTypes.FileType;
import com.intellij.psi.PsiFile;
import com.intellij.psi.tree.IElementType;
import de.kumpewo.autohotkey.AhkLanguage;
import de.kumpewo.autohotkey.psi.AhkTypes;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * Matching brace: ( ), [ ], { } and %...% (deref).
 * <p>
 * For "%" start and end are the same token. Since %...% cannot be nested (inside, a "%" can only be the end, see
 * Ahk.bnf deref_expr), start and end alternate within a statement: the first "%" since the line end (CRLF)
 * opens, the second closes, and so on. Strings, comments and directive arguments are separate tokens, so a "%"
 * inside them does not count.
 * <p>
 * The platform uses a PairedBraceMatcherAdapter registered as lang.braceMatcher directly (otherwise the
 * PairedBraceMatcher gets wrapped); only this way can findPair be overridden for "%".
 */
public class AhkBraceMatcher extends PairedBraceMatcherAdapter {

    public AhkBraceMatcher() {
        super(new Pairs(), AhkLanguage.INSTANCE);
    }

    /** For "%" decides from the position in the line whether it opens or closes; other braces as usual. */
    @Override
    public @Nullable BracePair findPair(boolean left, @NotNull HighlighterIterator iterator,
                                        @NotNull CharSequence fileText, @NotNull FileType fileType) {
        if (iterator.getTokenType() != AhkTypes.PERCENT) {
            return super.findPair(left, iterator, fileText, fileType);
        }
        boolean opening = percentsBefore(iterator) % 2 == 0;
        return opening == left ? Pairs.DEREF : null;
    }

    /** A %...% ends at the line end at the latest: stop the search there. */
    @Override
    public boolean shouldStopMatch(boolean forward, @NotNull IElementType braceType,
                                   @NotNull HighlighterIterator iterator) {
        if (braceType == AhkTypes.PERCENT && iterator.getTokenType() == AhkTypes.CRLF) return true;
        return super.shouldStopMatch(forward, braceType, iterator);
    }

    /**
     * Number of "%" between the last CRLF (or the start of the file) and the current token; even = the current
     * "%" opens, odd = it closes. Also used for typing pairs (AhkTypedHandler).
     */
    public static int percentsBefore(@NotNull HighlighterIterator iterator) {
        int count = 0;
        int steps = 0;
        while (true) {
            iterator.retreat();
            steps++;
            if (iterator.atEnd()) break;
            IElementType type = iterator.getTokenType();
            if (type == AhkTypes.CRLF) break;
            if (type == AhkTypes.PERCENT) count++;
        }
        // Reset the iterator to the starting token (the caller keeps working with it).
        for (int i = 0; i < steps; i++) iterator.advance();
        return count;
    }

    /** The brace pairs of the language. */
    private static final class Pairs implements PairedBraceMatcher {
        static final BracePair DEREF = new BracePair(AhkTypes.PERCENT, AhkTypes.PERCENT, false);
        private final BracePair[] pairs = {
                new BracePair(AhkTypes.L_PAREN, AhkTypes.R_PAREN, false),
                new BracePair(AhkTypes.L_BRACKET, AhkTypes.R_BRACKET, false),
                new BracePair(AhkTypes.L_BRACE, AhkTypes.R_BRACE, true),
                DEREF,
        };

        /** All pairs; only { } is structural. */
        @Override
        public BracePair @NotNull [] getPairs() {
            return pairs;
        }

        /** A closing brace may always be inserted automatically. */
        @Override
        public boolean isPairedBracesAllowedBeforeType(@NotNull IElementType lbraceType,
                                                       @Nullable IElementType contextType) {
            return true;
        }

        /** The construct starts at the opening brace. */
        @Override
        public int getCodeConstructStart(PsiFile file, int openingBraceOffset) {
            return openingBraceOffset;
        }
    }
}
