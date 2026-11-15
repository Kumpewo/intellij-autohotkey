package de.kumpewo.autohotkey.navigation;

import com.intellij.navigation.ChooseByNameContributorEx;
import com.intellij.navigation.GotoClassContributor;
import com.intellij.navigation.NavigationItem;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.psi.PsiFile;
import com.intellij.psi.PsiManager;
import com.intellij.psi.search.GlobalSearchScope;
import com.intellij.util.Processor;
import com.intellij.util.indexing.FileBasedIndex;
import com.intellij.util.indexing.FindSymbolParameters;
import com.intellij.util.indexing.IdFilter;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * Go to Symbol (Navigate > Symbol, Ctrl+Alt+Shift+N, "Symbols" in Search Everywhere): all definitions of the
 * AutoHotkey files in the search scope ({@link AhkSymbols}); project files, the AutoHotkey Lib only with
 * "Include non-project items" (the platform passes the scope). A free search, not a jump target: the user picks the
 * entry. Implements {@link GotoClassContributor} for the qualified names, so {@code Outer.Method} can be typed.
 * {@link AhkGotoClassContributor} restricts it to classes.
 */
public class AhkGotoSymbolContributor implements ChooseByNameContributorEx, GotoClassContributor {

    /** Does the contributor list this symbol? Here: all. */
    protected boolean accepts(@NotNull AhkSymbols.Symbol symbol) {
        return true;
    }

    /** All names in the index for the scope (may contain names of other kinds; they find no elements). */
    @Override
    public void processNames(@NotNull Processor<? super String> processor, @NotNull GlobalSearchScope scope,
                             @Nullable IdFilter filter) {
        FileBasedIndex.getInstance().processAllKeys(AhkSymbolIndex.NAME, processor, scope, filter);
    }

    /** The symbols with exactly this name in the files of the index within the scope. */
    @Override
    public void processElementsWithName(@NotNull String name, @NotNull Processor<? super NavigationItem> processor,
                                        @NotNull FindSymbolParameters parameters) {
        GlobalSearchScope scope = parameters.getSearchScope();
        PsiManager manager = PsiManager.getInstance(parameters.getProject());
        for (VirtualFile file : FileBasedIndex.getInstance().getContainingFiles(AhkSymbolIndex.NAME, name, scope)) {
            PsiFile psi = manager.findFile(file);
            if (psi == null) continue;
            for (AhkSymbols.Symbol symbol : AhkSymbols.collect(psi)) {
                if (symbol.name().equals(name) && accepts(symbol) && !processor.process(new AhkSymbolItem(symbol))) return;
            }
        }
    }

    /** {@code Outer.Inner.Method} for entries of this plugin. */
    @Override
    public @Nullable String getQualifiedName(@NotNull NavigationItem item) {
        return item instanceof AhkSymbolItem symbolItem ? symbolItem.symbol().qualifiedName() : null;
    }

    /** Classes and members are separated by a dot. */
    @Override
    public @NotNull String getQualifiedNameSeparator() {
        return ".";
    }
}
