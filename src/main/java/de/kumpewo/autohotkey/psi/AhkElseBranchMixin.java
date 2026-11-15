package de.kumpewo.autohotkey.psi;

import com.intellij.extapi.psi.ASTWrapperPsiElement;
import com.intellij.lang.ASTNode;
import org.jetbrains.annotations.NotNull;

/**
 * ELSE_BRANCH starts with the line breaks before "else" ({@code else_branch ::= CRLF* KEYWORD_ELSE ...}).
 * The text offset of the element is therefore the keyword "else", not its start: otherwise the header line
 * (sticky lines) or the navigation target would be on the line before.
 */
public abstract class AhkElseBranchMixin extends ASTWrapperPsiElement {

    public AhkElseBranchMixin(@NotNull ASTNode node) {
        super(node);
    }

    /** Offset of the keyword {@code else}. */
    @Override
    public int getTextOffset() {
        ASTNode keyword = getNode().findChildByType(AhkTypes.KEYWORD_ELSE);
        return keyword != null ? keyword.getStartOffset() : super.getTextOffset();
    }
}
