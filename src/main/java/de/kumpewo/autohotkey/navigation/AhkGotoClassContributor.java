package de.kumpewo.autohotkey.navigation;

import org.jetbrains.annotations.NotNull;

/** Go to Class (Navigate > Class, Ctrl+N, "Classes" in Search Everywhere): the classes among {@link AhkSymbols}. */
public class AhkGotoClassContributor extends AhkGotoSymbolContributor {

    /** Only classes (also nested ones, {@code Outer.Inner}). */
    @Override
    protected boolean accepts(@NotNull AhkSymbols.Symbol symbol) {
        return symbol.kind() == AhkSymbols.Kind.CLASS;
    }
}
