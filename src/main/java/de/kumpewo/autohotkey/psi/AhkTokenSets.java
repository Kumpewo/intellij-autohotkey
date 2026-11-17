package de.kumpewo.autohotkey.psi;

import com.intellij.psi.tree.TokenSet;

/** Token sets used by the parser definition and other components. */
public interface AhkTokenSets {
    TokenSet COMMENTS = TokenSet.create(AhkTypes.LINE_COMMENT, AhkTypes.BLOCK_COMMENT, AhkTypes.BAD_COMMENT,
            AhkTypes.CONTINUATION_OPTIONS);
    TokenSet STRINGS = TokenSet.create(AhkTypes.STRING);
}
