package de.kumpewo.autohotkey.navigation;

import com.intellij.lang.refactoring.NamesValidator;
import com.intellij.openapi.project.Project;
import com.intellij.psi.tree.IElementType;
import de.kumpewo.autohotkey.parser.AhkLexerAdapter;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.regex.Pattern;

/**
 * Checks new names in the Rename dialog (lang.namesValidator): a valid AutoHotkey name (letters, digits,
 * underscore and characters from U+0080, not starting with a digit) that is no reserved word of the lexer
 * ({@code if}, {@code class}, {@code this}, {@code and} ...).
 */
public class AhkNamesValidator implements NamesValidator {

    /** Pattern of a name, in a holder: no static initialization in the extension class. */
    private static final class Tables {
        static final Pattern NAME = Pattern.compile("[A-Za-z_\\u0080-\\uFFFF][A-Za-z0-9_\\u0080-\\uFFFF]*");
    }

    /** A reserved word: the lexer reads the whole name as one keyword token. */
    @Override
    public boolean isKeyword(@NotNull String name, @Nullable Project project) {
        AhkLexerAdapter lexer = new AhkLexerAdapter();
        lexer.start(name);
        IElementType type = lexer.getTokenType();
        return type != null && lexer.getTokenEnd() == name.length() && type.toString().contains("KEYWORD_");
    }

    /** A valid name that is no reserved word. */
    @Override
    public boolean isIdentifier(@NotNull String name, @Nullable Project project) {
        return Tables.NAME.matcher(name).matches() && !isKeyword(name, project);
    }
}
