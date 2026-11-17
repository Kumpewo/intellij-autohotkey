package de.kumpewo.autohotkey.navigation;

import com.intellij.lang.refactoring.RefactoringSupportProvider;
import com.intellij.psi.PsiElement;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * Refactoring support (lang.refactoringSupport): local variables and parameters of functions are renamed
 * in place in the editor, all other definitions in the Rename dialog with its forced preview (AhkRenameProcessor).
 * Other refactorings are not offered.
 */
public class AhkRefactoringSupportProvider extends RefactoringSupportProvider {

    /** In-place rename only for local variables and parameters. */
    @Override
    public boolean isInplaceRenameAvailable(@NotNull PsiElement element, @Nullable PsiElement context) {
        return element instanceof AhkLocalVariable;
    }

    /** No inline rename of members. */
    @Override
    public boolean isMemberInplaceRenameAvailable(@NotNull PsiElement element, @Nullable PsiElement context) {
        return false;
    }
}
