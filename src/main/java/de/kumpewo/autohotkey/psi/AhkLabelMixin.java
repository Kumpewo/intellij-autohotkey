package de.kumpewo.autohotkey.psi;

import com.intellij.extapi.psi.ASTWrapperPsiElement;
import com.intellij.icons.AllIcons;
import com.intellij.lang.ASTNode;
import com.intellij.navigation.ItemPresentation;
import com.intellij.psi.PsiElement;
import org.jetbrains.annotations.NonNls;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import javax.swing.*;

/** Label "Name:" (target of goto, break and continue). Shown in the structure view. */
public abstract class AhkLabelMixin extends ASTWrapperPsiElement implements ItemPresentation, AhkNamedElement {

    public AhkLabelMixin(@NotNull ASTNode node) {
        super(node);
    }

    /** Rename: replaces the name token of the label. */
    @Override
    public PsiElement setName(@NonNls @NotNull String name) {
        AhkRenames.replaceLeaf(getNameIdentifier(), name);
        return this;
    }

    /** Returns the label name without the colon, or {@code null}. */
    @Override
    public String getName() {
        PsiElement idElement = getNameIdentifier();
        return idElement != null ? idElement.getText() : null;
    }

    /** The label name is the IDENTIFIER token before the colon. */
    @Override
    public PsiElement getNameIdentifier() {
        return findChildByType(AhkTypes.IDENTIFIER);
    }

    /** Navigation target is the name. */
    @Override
    public int getTextOffset() {
        PsiElement id = getNameIdentifier();
        return id != null ? id.getTextOffset() : super.getTextOffset();
    }

    /** Text in the structure view, e.g. "Name:". */
    @Override
    public @NotNull String getPresentableText() {
        String name = getName();
        return name != null ? name + ":" : ":";
    }

    /** Icon in the structure view. */
    @Nullable
    @Override
    public Icon getIcon(boolean unused) {
        return AllIcons.Nodes.Symlink;
    }
}
