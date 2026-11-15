package de.kumpewo.autohotkey.navigation;

import com.intellij.lang.cacheBuilder.WordsScanner;
import com.intellij.lang.findUsages.FindUsagesProvider;
import com.intellij.psi.PsiElement;
import com.intellij.psi.PsiNamedElement;
import de.kumpewo.autohotkey.AhkBundle;
import de.kumpewo.autohotkey.psi.AhkClass;
import de.kumpewo.autohotkey.psi.AhkClassProperty;
import de.kumpewo.autohotkey.psi.AhkFunc;
import de.kumpewo.autohotkey.psi.AhkLabel;
import de.kumpewo.autohotkey.psi.AhkNamedElement;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * Enables Find Usages (Alt+F7) for functions, methods, classes, properties, fields and labels. The usages are
 * collected by {@link AhkReferencesSearcher}; this class only names the elements in the dialog and the usage view.
 * No words scanner: the search does not use the word index (it is case-sensitive, AutoHotkey names are not).
 */
public class AhkFindUsagesProvider implements FindUsagesProvider {

    /** No words scanner, see class comment. */
    @Override
    public @Nullable WordsScanner getWordsScanner() {
        return null;
    }

    /** Definitions with a name, and fields. */
    @Override
    public boolean canFindUsagesFor(@NotNull PsiElement element) {
        return element instanceof AhkNamedElement named && named.getName() != null || element instanceof AhkFieldTarget;
    }

    /** No help topic. */
    @Override
    public @Nullable String getHelpId(@NotNull PsiElement element) {
        return null;
    }

    /** Kind of the element ("function", "method", "class" ...). */
    @Override
    public @NotNull String getType(@NotNull PsiElement element) {
        if (element instanceof AhkFunc) {
            boolean method = com.intellij.psi.util.PsiTreeUtil.getParentOfType(element, AhkClass.class) != null;
            return AhkBundle.message(method ? "findUsages.type.method" : "findUsages.type.function");
        }
        if (element instanceof AhkClass) return AhkBundle.message("findUsages.type.class");
        if (element instanceof AhkClassProperty) return AhkBundle.message("findUsages.type.property");
        if (element instanceof AhkFieldTarget) return AhkBundle.message("findUsages.type.field");
        if (element instanceof AhkLabel) return AhkBundle.message("findUsages.type.label");
        return "";
    }

    /** The name. */
    @Override
    public @NotNull String getDescriptiveName(@NotNull PsiElement element) {
        String name = element instanceof PsiNamedElement named ? named.getName() : null;
        return name == null ? "" : name;
    }

    /** The name. */
    @Override
    public @NotNull String getNodeText(@NotNull PsiElement element, boolean useFullName) {
        return getDescriptiveName(element);
    }
}
