package de.kumpewo.autohotkey.navigation;

import com.intellij.openapi.util.TextRange;
import com.intellij.patterns.PlatformPatterns;
import com.intellij.psi.*;
import com.intellij.util.ProcessingContext;
import de.kumpewo.autohotkey.psi.*;
import org.jetbrains.annotations.NotNull;

/**
 * Jump targets: "goto Done", "goto 'Done'", "goto('Done')", "break Outer", "continue Outer" refer to the label
 * (AhkLabelReference). A name in parentheses ("goto(target)") is a variable and gets no reference.
 */
public class AhkLabelReferenceContributor extends PsiReferenceContributor {

    /** Registers providers for a plain name and for a string as the jump target. */
    @Override
    public void registerReferenceProviders(@NotNull PsiReferenceRegistrar registrar) {
        registrar.registerReferenceProvider(PlatformPatterns.psiElement(AhkRefExpr.class), new PsiReferenceProvider() {
            @Override
            public PsiReference @NotNull [] getReferencesByElement(@NotNull PsiElement element,
                                                                   @NotNull ProcessingContext context) {
                PsiElement parent = element.getParent();
                if (!(parent instanceof AhkGotoStmt || parent instanceof AhkJumpStmt)) return PsiReference.EMPTY_ARRAY;
                return new PsiReference[]{new AhkLabelReference(element, new TextRange(0, element.getTextLength()))};
            }
        });
        registrar.registerReferenceProvider(PlatformPatterns.psiElement(AhkLiteralExpr.class), new PsiReferenceProvider() {
            @Override
            public PsiReference @NotNull [] getReferencesByElement(@NotNull PsiElement element,
                                                                   @NotNull ProcessingContext context) {
                PsiElement parent = element.getParent();
                if (parent instanceof AhkParenExpr) parent = parent.getParent();
                if (!(parent instanceof AhkGotoStmt || parent instanceof AhkJumpStmt)) return PsiReference.EMPTY_ARRAY;
                PsiElement token = element.getFirstChild();
                if (token == null || token.getNode().getElementType() != AhkTypes.STRING
                        || element.getTextLength() < 2) {
                    return PsiReference.EMPTY_ARRAY;
                }
                return new PsiReference[]{new AhkLabelReference(element, new TextRange(1, element.getTextLength() - 1))};
            }
        });
    }
}
