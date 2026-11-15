package de.kumpewo.autohotkey.navigation;

import com.intellij.openapi.util.Condition;
import com.intellij.psi.PsiElement;
import de.kumpewo.autohotkey.include.AhkIncludeDirectoryTarget;

/**
 * Forbids renaming targets that are no definitions (vetoRenameCondition, Z.20): the help target of a built-in name
 * and the directory hint of an {@code #Include}.
 */
public class AhkRenameVeto implements Condition<PsiElement> {

    /** True = rename is not possible. */
    @Override
    public boolean value(PsiElement element) {
        return element instanceof AhkHelpTarget || element instanceof AhkIncludeDirectoryTarget;
    }
}
