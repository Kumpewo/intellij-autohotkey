package de.kumpewo.autohotkey.navigation;

import com.intellij.icons.AllIcons;
import com.intellij.navigation.ItemPresentation;
import com.intellij.openapi.fileEditor.OpenFileDescriptor;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.psi.PsiElement;
import com.intellij.psi.PsiFile;
import com.intellij.psi.impl.FakePsiElement;
import de.kumpewo.autohotkey.psi.AhkDefinitions;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import javax.swing.*;

/**
 * Navigation target for a field of a class ({@code count := 0}, {@code static count := 0};
 * {@link AhkDefinitions#fields}): fields are no named PSI elements of their own, so this target stands for the name
 * and shows "name" with "Class X" in choosers, like the other definitions. It is the target of a name after the dot
 * ({@link AhkDefinitionReference}) and of the name in the defining statement itself ({@link AhkTargetElementEvaluator}:
 * Find Usages and Rename at the definition), and a node of the structure view.
 */
public final class AhkFieldTarget extends FakePsiElement {

    private final AhkDefinitions.Field field;

    /** The field this target stands for. */
    public @NotNull AhkDefinitions.Field field() {
        return field;
    }

    /** Target for the field. */
    public AhkFieldTarget(@NotNull AhkDefinitions.Field field) {
        this.field = field;
    }

    /** The name of the field. */
    public @NotNull PsiElement identifier() {
        return field.identifier();
    }

    /** The statement that defines the field. */
    @Override
    public PsiElement getParent() {
        return field.identifier().getParent();
    }

    /** The file of the field. */
    @Override
    public PsiFile getContainingFile() {
        return field.identifier().getContainingFile();
    }

    /** Rename: replaces the name of the field in its defining statement. */
    @Override
    public PsiElement setName(@NotNull String name) {
        de.kumpewo.autohotkey.psi.AhkRenames.replaceLeaf(field.identifier(), name);
        return this;
    }

    /** AutoHotkey: Find Usages looks up its provider (AhkFindUsagesProvider) by the language. */
    @Override
    public @NotNull com.intellij.lang.Language getLanguage() {
        return de.kumpewo.autohotkey.AhkLanguage.INSTANCE;
    }

    /** Name of the field. */
    @Override
    public @NotNull String getName() {
        return field.name();
    }

    /** Offset of the name (for sorting and navigation). */
    @Override
    public int getTextOffset() {
        return field.identifier().getTextOffset();
    }

    /** The name itself, e.g. for "show usages" or the navigation of the platform. */
    @Override
    public @NotNull PsiElement getNavigationElement() {
        return field.identifier();
    }

    /** Field icon. */
    @Override
    public @Nullable Icon getIcon(boolean open) {
        return AllIcons.Nodes.Field;
    }

    /** "name" with the class as location, like methods and properties. */
    @Override
    public @NotNull ItemPresentation getPresentation() {
        return AhkDefinitions.presentation(field.identifier(), field.name(), AllIcons.Nodes.Field);
    }

    /** Navigable while the file exists. */
    @Override
    public boolean canNavigate() {
        return field.identifier().isValid();
    }

    /** Opens the file at the name of the field. */
    @Override
    public void navigate(boolean requestFocus) {
        PsiFile file = getContainingFile();
        VirtualFile virtualFile = file == null ? null : file.getVirtualFile();
        if (virtualFile == null) return;
        new OpenFileDescriptor(file.getProject(), virtualFile, getTextOffset()).navigate(requestFocus);
    }

    /** Equal if it stands for the same name. */
    @Override
    public boolean equals(Object o) {
        return o instanceof AhkFieldTarget other && other.field.identifier().equals(field.identifier());
    }

    /** Hash of the name element. */
    @Override
    public int hashCode() {
        return field.identifier().hashCode();
    }
}
