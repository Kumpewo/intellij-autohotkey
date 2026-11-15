package de.kumpewo.autohotkey.navigation;

import com.intellij.lang.refactoring.RefactoringSupportProvider;
import com.intellij.psi.PsiElement;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * Refactoring support (lang.refactoringSupport, Z.20): no inline rename in the editor, so Shift+F6 always opens the
 * Rename dialog with its forced preview (AhkRenameProcessor). Other refactorings are not offered.
 */
public class AhkRefactoringSupportProvider extends RefactoringSupportProvider {

    /** No inline rename of local elements. */
    @Override
    public boolean isInplaceRenameAvailable(@NotNull PsiElement element, @Nullable PsiElement context) {
        return false;
    }

    /** No inline rename of members. */
    @Override
    public boolean isMemberInplaceRenameAvailable(@NotNull PsiElement element, @Nullable PsiElement context) {
        return false;
    }
}
