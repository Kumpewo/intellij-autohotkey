package de.kumpewo.autohotkey.parser;

import com.intellij.lexer.FlexAdapter;

/** Wraps the JFlex lexer generated from {@code Ahk.flex} ({@code _AhkLexer}) as an IntelliJ lexer. */
public class AhkLexerAdapter extends FlexAdapter {

    public AhkLexerAdapter() {
        super(new _AhkLexer(null));
    }

}
