package de.kumpewo.autohotkey.annotation;

import com.intellij.codeInspection.InspectionSuppressor;
import com.intellij.codeInspection.SuppressQuickFix;
import com.intellij.psi.PsiElement;
import com.intellij.psi.PsiFile;
import de.kumpewo.autohotkey.psi.AhkTypes;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * Suppresses the platform inspection "Non-ASCII characters" ({@link #NON_ASCII}) for names in AutoHotkey files:
 * AutoHotkey allows every non-ASCII character in names, e.g. a prefix like "§" for private members
 * ({@code ;@declare-private §*}). Strings and comments are still checked as configured.
 */
public final class AhkInspectionSuppressor implements InspectionSuppressor {

    /** Short name of the platform inspection "Non-ASCII characters". */
    static final String NON_ASCII = "NonAsciiCharacters";

    /** True for "Non-ASCII characters" on an identifier (the element itself or the leaf at its start). */
    @Override
    public boolean isSuppressedFor(@NotNull PsiElement element, @NotNull String toolId) {
        if (!NON_ASCII.equals(toolId)) return false;
        if (element.getNode() != null && element.getNode().getElementType() == AhkTypes.IDENTIFIER) return true;
        PsiFile file = element.getContainingFile();
        PsiElement leaf = file == null ? null : file.findElementAt(element.getTextRange().getStartOffset());
        return leaf != null && leaf.getNode().getElementType() == AhkTypes.IDENTIFIER
                && leaf.getTextRange().contains(element.getTextRange());
    }

    /** No "suppress" actions in the Alt+Enter menu (there is no suppression comment in AutoHotkey). */
    @Override
    public SuppressQuickFix @NotNull [] getSuppressActions(@Nullable PsiElement element, @NotNull String toolId) {
        return SuppressQuickFix.EMPTY_ARRAY;
    }
}
