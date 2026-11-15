package de.kumpewo.autohotkey.navigation;

import com.intellij.openapi.util.TextRange;
import com.intellij.psi.PsiElement;
import com.intellij.psi.PsiReferenceBase;
import de.kumpewo.autohotkey.psi.AhkLocals;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * Reference from an occurrence of a local variable or parameter (use or definition) to its target
 * {@link AhkLocalVariable} (Z.27). Created by AhkDefinitionReferenceContributor only where {@link AhkLocals#resolve}
 * finds a local variable.
 */
public final class AhkLocalReference extends PsiReferenceBase<PsiElement> {

    private final PsiElement identifier;

    /** Reference of {@code element} for its leaf {@code identifier}. */
    public AhkLocalReference(@NotNull PsiElement element, @NotNull PsiElement identifier) {
        super(element, rangeIn(element, identifier), false);
        this.identifier = identifier;
    }

    /** The range of the identifier inside the element. */
    private static TextRange rangeIn(PsiElement element, PsiElement identifier) {
        int start = identifier.getTextRange().getStartOffset() - element.getTextRange().getStartOffset();
        return new TextRange(start, start + identifier.getTextLength());
    }

    /** The local variable, or {@code null} if the code changed and the name is no longer local. */
    @Override
    public @Nullable PsiElement resolve() {
        AhkLocals.Local local = identifier.isValid() ? AhkLocals.resolve(identifier) : null;
        return local == null ? null : new AhkLocalVariable(local);
    }

    /** Same variable (targets are new objects, compared with equals). */
    @Override
    public boolean isReferenceTo(@NotNull PsiElement element) {
        return element instanceof AhkLocalVariable && element.equals(resolve());
    }

    /** Rename: replaces the name in the element (only the range of this reference). */
    @Override
    public PsiElement handleElementRename(@NotNull String newElementName) {
        return de.kumpewo.autohotkey.psi.AhkRenames.replaceRange(myElement, getRangeInElement(), newElementName);
    }

    /** No variants: completion is done by AhkCompletionContributor. */
    @Override
    public Object @NotNull [] getVariants() {
        return EMPTY_ARRAY;
    }
}
