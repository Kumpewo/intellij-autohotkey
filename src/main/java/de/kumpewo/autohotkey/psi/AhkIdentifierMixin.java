package de.kumpewo.autohotkey.psi;

import com.intellij.extapi.psi.ASTWrapperPsiElement;
import com.intellij.lang.ASTNode;
import com.intellij.navigation.ItemPresentation;
import com.intellij.psi.PsiElement;
import org.jetbrains.annotations.NonNls;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import javax.swing.*;

/**
 * Mixin for an identifier as a named element (the element is its own name).
 * Currently unused: no grammar rule refers to it.
 */
public abstract class AhkIdentifierMixin extends ASTWrapperPsiElement implements ItemPresentation, AhkNamedElement {

    public AhkIdentifierMixin(@NotNull ASTNode node) {
        super(node);
    }

    /** Renaming is not supported; the element stays unchanged. */
    @Override
    public PsiElement setName(@NonNls @NotNull String name) {
        return this;
    }

    /** Returns the text of the identifier. */
    @Override
    public String getName() {
        // The element itself is the identifier
        PsiElement idElement = getNameIdentifier();
        return idElement != null ? idElement.getText() : null;
    }

    /** The element itself is the name. */
    @Override
    public PsiElement getNameIdentifier() {
        return this;
    }

    /** Text in the structure view. */
    @Override
    public @NotNull String getPresentableText() {
        String name = getName();
        return name != null ? name : "";
    }

    /** Icon in the structure view. */
    @Nullable
    @Override
    public Icon getIcon(boolean unused) {
        return com.intellij.icons.AllIcons.Nodes.Class;
    }

}
