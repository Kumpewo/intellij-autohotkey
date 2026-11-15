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
 * Mixin of a class definition ({@code class Name [extends Base] { … }}): name, navigation target, presentation
 * in the structure view and in choosers, and the reference to the base class.
 */
public abstract class AhkClassMixin extends ASTWrapperPsiElement implements ItemPresentation, AhkNamedElement {

    public AhkClassMixin(@NotNull ASTNode node) {
        super(node);
    }

    /** Rename (Z.20): replaces the name token of the class. */
    @Override
    public PsiElement setName(@NonNls @NotNull String name) {
        AhkRenames.replaceLeaf(getNameIdentifier(), name);
        return this;
    }

    /** Returns the class name, or {@code null} if it is missing. */
    @Override
    public String getName() {
        // Takes the IDENTIFIER token directly from the class node
        PsiElement idElement = getNameIdentifier();
        return idElement != null ? idElement.getText() : null;
    }

    /** The class name is the first IDENTIFIER token (the base class follows after {@code extends}). */
    @Override
    public PsiElement getNameIdentifier() {
        return findChildByType(AhkTypes.IDENTIFIER);
    }

    /** Navigation target is the name, not the keyword {@code class}. */
    @Override
    public int getTextOffset() {
        PsiElement id = getNameIdentifier();
        return id != null ? id.getTextOffset() : super.getTextOffset();
    }

    /** Presentation in choosers (name plus enclosing class, see {@link AhkDefinitions}). */
    @Override
    public ItemPresentation getPresentation() {
        return AhkDefinitions.presentation(this, getPresentableText(), getIcon(false));
    }

    /** References from psi.referenceContributor: the base class after {@code extends}. */
    @Override
    public com.intellij.psi.PsiReference @NotNull [] getReferences() {
        return com.intellij.psi.impl.source.resolve.reference.ReferenceProvidersRegistry
                .getReferencesFromProviders(this);
    }

    /** Text in the structure view, e.g. "Class Name". */
    @Override
    public @NotNull String getPresentableText() {
        String name = getName();
        return name != null ? "Class " + name : "anonymous class";
    }

    /** Icon in the structure view. */
    @Nullable
    @Override
    public Icon getIcon(boolean unused) {
        return com.intellij.icons.AllIcons.Nodes.Class;
    }

}
