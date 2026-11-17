package de.kumpewo.autohotkey.psi;

import com.intellij.openapi.util.TextRange;
import com.intellij.psi.PsiElement;
import com.intellij.psi.impl.source.tree.LeafPsiElement;
import com.intellij.util.IncorrectOperationException;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * Text replacement for Rename: replaces a range inside one leaf of an element, e.g. the name of a function,
 * a name after the dot or a label name in quotes ({@code goto "Target"}). Works on the leaf text, so the tree keeps
 * its structure (the replaced leaf has the same token type).
 */
public final class AhkRenames {

    private AhkRenames() {
    }

    /** Replaces the whole leaf {@code identifier} (a name token) with {@code newName}. */
    public static void replaceLeaf(@Nullable PsiElement identifier, @NotNull String newName) {
        if (identifier == null) throw new IncorrectOperationException("No name to rename");
        replaceRange(identifier, new TextRange(0, identifier.getTextLength()), newName);
    }

    /**
     * Replaces {@code rangeInElement} of {@code element} with {@code newText}. The range must lie inside one leaf;
     * returns the element (it stays valid, only its leaf is replaced).
     */
    public static @NotNull PsiElement replaceRange(@NotNull PsiElement element, @NotNull TextRange rangeInElement,
                                                   @NotNull String newText) {
        int start = element.getTextRange().getStartOffset() + rangeInElement.getStartOffset();
        PsiElement leaf = element.getFirstChild() == null ? element : element.getContainingFile().findElementAt(start);
        if (!(leaf instanceof LeafPsiElement leafPsi)) throw new IncorrectOperationException("No leaf at " + start);
        TextRange leafRange = leaf.getTextRange();
        int from = start - leafRange.getStartOffset();
        int to = from + rangeInElement.getLength();
        if (to > leafRange.getLength()) throw new IncorrectOperationException("Range crosses a token: " + rangeInElement);
        String text = leaf.getText();
        leafPsi.replaceWithText(text.substring(0, from) + newText + text.substring(to));
        return element;
    }
}
