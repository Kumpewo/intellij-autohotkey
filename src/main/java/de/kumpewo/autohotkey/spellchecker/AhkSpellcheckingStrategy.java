package de.kumpewo.autohotkey.spellchecker;

import com.intellij.lang.ASTNode;
import com.intellij.openapi.project.DumbAware;
import com.intellij.openapi.util.TextRange;
import com.intellij.psi.PsiElement;
import com.intellij.psi.tree.IElementType;
import com.intellij.spellchecker.inspections.PlainTextSplitter;
import com.intellij.spellchecker.tokenizer.SpellcheckingStrategy;
import com.intellij.spellchecker.tokenizer.TokenConsumer;
import com.intellij.spellchecker.tokenizer.Tokenizer;
import de.kumpewo.autohotkey.psi.AhkTypes;
import org.jetbrains.annotations.NotNull;


/**
 * Spell checking (spellchecker.support) of the prose in AutoHotkey files:
 * <ul>
 *   <li>comments, except pragma and compiler directive comments ({@code ;@declare-type}, {@code ;@Ahk2Exe-...})</li>
 *   <li>strings and continuation sections, without the quotes and the escape sequences ({@code `n} ...)</li>
 *   <li>the replacement text of hotstrings ({@code ::btw::by the way}, token HOTSTRING_TEXT; with the option
 *       {@code X} the lexer reads code instead)</li>
 * </ul>
 * Names in the code (functions, classes, variables) are not checked: the "change to" fix would change a single
 * occurrence only; names are changed with Rename (AhkRenameProcessor). Built-in names and AutoHotkey terms in the checked text are known
 * through {@link AhkDictionaryProvider}.
 */
public class AhkSpellcheckingStrategy extends SpellcheckingStrategy implements DumbAware {

    /** Tokenizers in a separate class: no static initialization in the extension class. */
    private static final class Tables {
        /** Strings: the text between the quotes without escape sequences. */
        static final Tokenizer<PsiElement> STRING = new Tokenizer<>() {
            @Override
            public void tokenize(@NotNull PsiElement element, @NotNull TokenConsumer consumer) {
                String text = element.getText();
                int start = text.isEmpty() ? 0 : 1;
                int end = text.length();
                if (end > start && text.charAt(end - 1) == text.charAt(0)
                        && element.getNode().getElementType() == AhkTypes.STRING) {
                    end--;   // closing quote
                }
                consumeWithoutEscapes(element, text, start, end, consumer);
            }
        };

        /** Replacement text of a hotstring, without escape sequences. */
        static final Tokenizer<PsiElement> HOTSTRING_TEXT = new Tokenizer<>() {
            @Override
            public void tokenize(@NotNull PsiElement element, @NotNull TokenConsumer consumer) {
                String text = element.getText();
                consumeWithoutEscapes(element, text, 0, text.length(), consumer);
            }
        };
    }

    /** The tokenizer for comments, strings and hotstrings; nothing for other elements. */
    @Override
    public @NotNull Tokenizer getTokenizer(PsiElement element) {
        ASTNode node = element.getNode();
        if (node == null) return EMPTY_TOKENIZER;
        IElementType type = node.getElementType();
        if (type == AhkTypes.LINE_COMMENT) {
            return element.getText().trim().startsWith(";@") ? EMPTY_TOKENIZER : myCommentTokenizer;
        }
        if (type == AhkTypes.BLOCK_COMMENT || type == AhkTypes.BAD_COMMENT) return myCommentTokenizer;
        if (type == AhkTypes.STRING || type == AhkTypes.UNCLOSED_STRING) return Tables.STRING;
        if (type == AhkTypes.HOTSTRING_TEXT) return Tables.HOTSTRING_TEXT;
        return EMPTY_TOKENIZER;
    }

    /**
     * Passes the parts of {@code text} between {@code start} and {@code end} that are not escape sequences (backtick
     * and the next character) to the consumer.
     */
    private static void consumeWithoutEscapes(PsiElement element, String text, int start, int end,
                                              TokenConsumer consumer) {
        int segment = start;
        for (int i = start; i < end; i++) {
            if (text.charAt(i) != '`') continue;
            if (i > segment) {
                consumer.consumeToken(element, text, false, 0, new TextRange(segment, i), PlainTextSplitter.getInstance());
            }
            i++;   // the escaped character
            segment = i + 1;
        }
        if (end > segment) {
            consumer.consumeToken(element, text, false, 0, new TextRange(segment, end), PlainTextSplitter.getInstance());
        }
    }
}
