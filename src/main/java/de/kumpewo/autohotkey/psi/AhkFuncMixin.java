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
 * Mixin of a function or method definition: name, navigation target, presentation in the structure view and
 * in choosers.
 */
public abstract class AhkFuncMixin extends ASTWrapperPsiElement implements ItemPresentation, AhkNamedElement {

    public AhkFuncMixin(@NotNull ASTNode node) {
        super(node);
    }

    /** Rename (Z.20): replaces the name token of the function. */
    @Override
    public PsiElement setName(@NonNls @NotNull String name) {
        AhkRenames.replaceLeaf(getNameIdentifier(), name);
        return this;
    }

    /** Returns the function name, or {@code null}. */
    @Override
    public String getName() {
        // Takes the IDENTIFIER token directly from the function node
        PsiElement idElement = getNameIdentifier();
        return idElement != null ? idElement.getText() : null;
    }

    /** The function name is the first IDENTIFIER token. */
    @Override
    public PsiElement getNameIdentifier() {
        return findChildByType(AhkTypes.IDENTIFIER);
    }


    /** Navigation target is the name (not a preceding {@code static}). */
    @Override
    public int getTextOffset() {
        PsiElement id = getNameIdentifier();
        return id != null ? id.getTextOffset() : super.getTextOffset();
    }

    /** Presentation in choosers (name plus enclosing class). */
    @Override
    public ItemPresentation getPresentation() {
        return AhkDefinitions.presentation(this, getPresentableText(), getIcon(false));
    }

    /** Text in the structure view, e.g. "Name()". */
    @Override
    public @NotNull String getPresentableText() {
        String name = getName();
        return name != null ? name + "()" : "anonymous";
    }

    /** Icon in the structure view. */
    @Nullable
    @Override
    public Icon getIcon(boolean unused) {
        return com.intellij.icons.AllIcons.Nodes.Method;
    }
}
