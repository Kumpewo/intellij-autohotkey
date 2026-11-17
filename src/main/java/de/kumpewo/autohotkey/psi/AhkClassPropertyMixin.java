package de.kumpewo.autohotkey.psi;

import com.intellij.extapi.psi.ASTWrapperPsiElement;
import com.intellij.icons.AllIcons;
import com.intellij.lang.ASTNode;
import com.intellij.navigation.ItemPresentation;
import com.intellij.psi.PsiElement;
import org.jetbrains.annotations.NonNls;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * Property of a class ({@code Name => x}, {@code Name { get … }}, {@code __Item[key] { … }}): name and text
 * offset for navigation.
 */
public abstract class AhkClassPropertyMixin extends ASTWrapperPsiElement implements AhkNamedElement {

    public AhkClassPropertyMixin(@NotNull ASTNode node) {
        super(node);
    }

    /** Rename: replaces the name token of the property. */
    @Override
    public PsiElement setName(@NonNls @NotNull String name) {
        AhkRenames.replaceLeaf(getNameIdentifier(), name);
        return this;
    }

    /** Returns the property name, or {@code null}. */
    @Override
    public String getName() {
        PsiElement id = getNameIdentifier();
        return id != null ? id.getText() : null;
    }

    /** The property name is the first IDENTIFIER token. */
    @Override
    public @Nullable PsiElement getNameIdentifier() {
        return findChildByType(AhkTypes.IDENTIFIER);
    }

    /** Navigation target is the name, not a preceding {@code static}. */
    @Override
    public int getTextOffset() {
        PsiElement id = getNameIdentifier();
        return id != null ? id.getTextOffset() : super.getTextOffset();
    }

    /** Presentation in choosers (name plus enclosing class). */
    @Override
    public ItemPresentation getPresentation() {
        String name = getName();
        return AhkDefinitions.presentation(this, name != null ? name : "?", AllIcons.Nodes.Property);
    }
}
