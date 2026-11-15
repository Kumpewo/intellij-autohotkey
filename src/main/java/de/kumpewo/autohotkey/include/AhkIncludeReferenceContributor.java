package de.kumpewo.autohotkey.include;

import com.intellij.openapi.util.TextRange;
import com.intellij.patterns.PlatformPatterns;
import com.intellij.psi.PsiElement;
import com.intellij.psi.PsiReference;
import com.intellij.psi.PsiReferenceContributor;
import com.intellij.psi.PsiReferenceProvider;
import com.intellij.psi.PsiReferenceRegistrar;
import com.intellij.util.ProcessingContext;
import de.kumpewo.autohotkey.psi.AhkDirective;
import org.jetbrains.annotations.NotNull;

/**
 * Reference from the argument of an {@code #Include}/{@code #IncludeAgain} to the file ({@link AhkIncludeReference}).
 */
public final class AhkIncludeReferenceContributor extends PsiReferenceContributor {

    /** Registers the provider for directives. */
    @Override
    public void registerReferenceProviders(@NotNull PsiReferenceRegistrar registrar) {
        registrar.registerReferenceProvider(PlatformPatterns.psiElement(AhkDirective.class), new PsiReferenceProvider() {
            @Override
            public PsiReference @NotNull [] getReferencesByElement(@NotNull PsiElement element,
                                                                   @NotNull ProcessingContext context) {
                AhkDirective directive = (AhkDirective) element;
                if (!AhkInclude.isInclude(directive)) return PsiReference.EMPTY_ARRAY;
                TextRange range = AhkInclude.argumentRange(directive);
                if (range == null) return PsiReference.EMPTY_ARRAY;
                return new PsiReference[]{new AhkIncludeReference(directive, range)};
            }
        });
    }
}
