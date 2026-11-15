package de.kumpewo.autohotkey.navigation;

import com.intellij.openapi.util.TextRange;
import com.intellij.psi.*;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * Early draft of a name reference (first named element with the same name in the file).
 * Unused: {@link AhkReferenceContributor} is commented out in {@code plugin.xml}; navigation is done by
 * {@link AhkDefinitionReference}.
 */
public class AhkReference extends PsiReferenceBase<PsiElement> {

    public AhkReference(@NotNull PsiElement element) {
        super(element, new TextRange(0, element.getTextLength()));
    }

    /** The name identifier of the first named element with the same name, or {@code null}. */
    @Nullable
    @Override
    public PsiElement resolve() {
        String targetName = myElement.getText();
        PsiFile file = myElement.getContainingFile();
        if (file == null) return null;

        final PsiElement[] resolvedTarget = {null};

        file.accept(new PsiRecursiveElementWalkingVisitor() {
            @Override
            public void visitElement(@NotNull PsiElement element) {
                super.visitElement(element);

                // Check whether the element has a name and is an identifier owner
                if (element instanceof PsiNameIdentifierOwner owner) {
                    String name = owner.getName();

                    if (targetName.equalsIgnoreCase(name)) {
                        PsiElement nameIdentifier = owner.getNameIdentifier();

                        // Prevent the identifier from resolving to itself
                        if (nameIdentifier != null && !nameIdentifier.equals(myElement)) {
                            resolvedTarget[0] = nameIdentifier; // jump directly to the name
                            stopWalking();
                        }
                    }
                }
            }
        });

        return resolvedTarget[0];
    }

    /** No completion variants. */
    @NotNull
    @Override
    public Object[] getVariants() {
        // Optional: completion suggestions
        return EMPTY_ARRAY;
    }
}
