package de.kumpewo.autohotkey.completion;

import com.intellij.codeInsight.editorActions.SimpleTokenSetQuoteHandler;
import de.kumpewo.autohotkey.psi.AhkTypes;

/**
 * Quotes in pairs: typing " or ' inserts the closing one as well, with the caret in between; typing it again at
 * the end skips over it. Only where the lexer sees a string (STRING, UNCLOSED_STRING), so not in comments and in
 * the replacement text of hotstrings.
 */
public class AhkQuoteHandler extends SimpleTokenSetQuoteHandler {

    public AhkQuoteHandler() {
        super(AhkTypes.STRING, AhkTypes.UNCLOSED_STRING);
    }
}
