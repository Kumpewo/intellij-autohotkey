package de.kumpewo.autohotkey.highlighting;

import com.intellij.lexer.Lexer;
import com.intellij.openapi.editor.colors.TextAttributesKey;
import com.intellij.openapi.fileTypes.SyntaxHighlighterBase;
import com.intellij.psi.tree.IElementType;
import de.kumpewo.autohotkey.parser.AhkLexerAdapter;
import de.kumpewo.autohotkey.psi.AhkTypes;
import org.jetbrains.annotations.NotNull;

import java.util.HashMap;
import java.util.Map;

/**
 * Lexer-based highlighting: only strings, numbers, comments, hotkeys and hotstrings. Everything that depends on
 * the role of a name (keywords, built-ins, definitions) is colored by {@code AhkAnnotator}.
 */
public class AhkSyntaxHighlighter extends SyntaxHighlighterBase {

    private static final Map<IElementType, TextAttributesKey> ATTRIBUTES = new HashMap<>();

    /** Fills the token-to-color table. */
    private static void initialize() {
        ATTRIBUTES.put(AhkTypes.STRING, AhkColors.STRING);
        ATTRIBUTES.put(AhkTypes.NUMBER, AhkColors.NUMBER);
        ATTRIBUTES.put(AhkTypes.LINE_COMMENT, AhkColors.COMMENT);
        ATTRIBUTES.put(AhkTypes.BLOCK_COMMENT, AhkColors.COMMENT);
        ATTRIBUTES.put(AhkTypes.BAD_COMMENT, AhkColors.COMMENT);
        ATTRIBUTES.put(AhkTypes.HOTKEY, AhkColors.AHK_HOTKEY);
        ATTRIBUTES.put(AhkTypes.REMAP_KEY, AhkColors.AHK_HOTKEY);
        ATTRIBUTES.put(AhkTypes.HOTSTRING, AhkColors.AHK_HOTSTRING);
    }

    /** The lexer used for highlighting (the same as for parsing). */
    @NotNull
    @Override
    public Lexer getHighlightingLexer() {
        return new AhkLexerAdapter();
    }

    /** Color keys of a token type (empty for tokens colored elsewhere). */
    @NotNull
    @Override
    public TextAttributesKey @NotNull [] getTokenHighlights(IElementType tokenType) {
        if (ATTRIBUTES.isEmpty()) {
            initialize();
        }
        return pack(ATTRIBUTES.get(tokenType));
    }
}
