package de.kumpewo.autohotkey.navigation;

import com.intellij.patterns.PlatformPatterns;
import com.intellij.psi.*;
import com.intellij.util.ProcessingContext;
import de.kumpewo.autohotkey.psi.AhkFunc;
import org.jetbrains.annotations.NotNull;

/**
 * Early draft that registers {@link AhkReference}. Unused: commented out in {@code plugin.xml}.
 */
public class AhkReferenceContributor extends PsiReferenceContributor {
    /** Registers an {@link AhkReference} for every function definition. */
    @Override
    public void registerReferenceProviders(@NotNull PsiReferenceRegistrar registrar) {
        registrar.registerReferenceProvider(
                PlatformPatterns.psiElement(AhkFunc.class),
                new PsiReferenceProvider() {
                    @NotNull
                    @Override
                    public PsiReference @NotNull [] getReferencesByElement(@NotNull PsiElement element, @NotNull ProcessingContext context) {
                        return new PsiReference[]{new AhkReference(element)};
                    }
                }
        );
    }
}
