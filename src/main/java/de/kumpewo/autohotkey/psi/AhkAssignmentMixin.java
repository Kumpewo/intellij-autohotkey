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
 * Mixin for an assignment as a named element (name = assigned identifier).
 * Currently unused: no grammar rule refers to it.
 */
public abstract class AhkAssignmentMixin extends ASTWrapperPsiElement implements ItemPresentation, AhkNamedElement {

    public AhkAssignmentMixin(@NotNull ASTNode node) {
        super(node);
    }

    /** Renaming is not supported; the element stays unchanged. */
    @Override
    public PsiElement setName(@NonNls @NotNull String name) {
        return this;
    }

    /** Returns the text of the identifier, or {@code null}. */
    @Override
    public String getName() {
        // Takes the IDENTIFIER token directly from the node
        PsiElement idElement = getNameIdentifier();
        return idElement != null ? idElement.getText() : null;
    }

    /** Returns the first IDENTIFIER token among the children. */
    @Override
    public PsiElement getNameIdentifier() {
        return findChildByType(AhkTypes.IDENTIFIER);
    }

    /** Text in the structure view. */
    @Override
    public @NotNull String getPresentableText() {
        String name = getName();
        return name != null ? name : "anonymous";
    }

    /** Icon in the structure view. */
    @Nullable
    @Override
    public Icon getIcon(boolean unused) {
        return com.intellij.icons.AllIcons.Nodes.Class;
    }

}
