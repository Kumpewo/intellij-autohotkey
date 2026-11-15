package de.kumpewo.autohotkey.navigation;

import com.intellij.icons.AllIcons;
import com.intellij.navigation.ItemPresentation;
import com.intellij.openapi.fileEditor.OpenFileDescriptor;
import com.intellij.openapi.util.TextRange;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.psi.PsiElement;
import com.intellij.psi.PsiFile;
import com.intellij.psi.impl.FakePsiElement;
import com.intellij.psi.search.LocalSearchScope;
import com.intellij.psi.search.SearchScope;
import de.kumpewo.autohotkey.psi.AhkDefinitions;
import de.kumpewo.autohotkey.psi.AhkLocals;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import javax.swing.*;

/**
 * Target of a local variable or parameter of a function ({@link AhkLocals.Local}, Z.27): Ctrl+B leads to its first
 * definition, Find Usages and the highlighting of occurrences stay inside the function that owns it, Rename works
 * in place in the editor (AhkRefactoringSupportProvider). Every occurrence, also the definition, carries an
 * {@link AhkLocalReference}, so renaming the references renames everything and {@link #setName} has nothing left to
 * do.
 */
public final class AhkLocalVariable extends FakePsiElement {

    private final AhkLocals.Local local;

    /** Target for the local variable. */
    public AhkLocalVariable(@NotNull AhkLocals.Local local) {
        this.local = local;
    }

    /** The local variable. */
    public @NotNull AhkLocals.Local local() {
        return local;
    }

    /** The statement or parameter that defines the variable first. */
    @Override
    public PsiElement getParent() {
        return local.definition().getParent();
    }

    /** The file of the variable. */
    @Override
    public PsiFile getContainingFile() {
        return local.definition().getContainingFile();
    }

    /** Valid while its function and its definition are. */
    @Override
    public boolean isValid() {
        return local.scope().isValid() && local.definition().isValid();
    }

    /** Nothing to do: the definition is renamed through its reference like every other occurrence. */
    @Override
    public PsiElement setName(@NotNull String name) {
        return this;
    }

    /** AutoHotkey: Find Usages and Rename look up their providers by the language. */
    @Override
    public @NotNull com.intellij.lang.Language getLanguage() {
        return de.kumpewo.autohotkey.AhkLanguage.INSTANCE;
    }

    /** The name as written at the definition. */
    @Override
    public @NotNull String getName() {
        return local.name();
    }

    /** Only the function that owns the variable (search for Find Usages and in-place rename). */
    @Override
    public @NotNull SearchScope getUseScope() {
        return new LocalSearchScope(local.scope());
    }

    /** Range of the first definition. */
    @Override
    public TextRange getTextRange() {
        return local.definition().getTextRange();
    }

    /** Offset of the first definition (for sorting and navigation). */
    @Override
    public int getTextOffset() {
        return local.definition().getTextOffset();
    }

    /** The first definition, e.g. for "show usages" and the navigation of the platform. */
    @Override
    public @NotNull PsiElement getNavigationElement() {
        return local.definition();
    }

    /** Parameter or variable icon. */
    @Override
    public @Nullable Icon getIcon(boolean open) {
        return local.parameter() ? AllIcons.Nodes.Parameter : AllIcons.Nodes.Variable;
    }

    /** "name" with the enclosing class as location, like the other definitions. */
    @Override
    public @NotNull ItemPresentation getPresentation() {
        return AhkDefinitions.presentation(local.definition(), local.name(), getIcon(false));
    }

    /** Navigable while the file exists. */
    @Override
    public boolean canNavigate() {
        return isValid();
    }

    /** Opens the file at the first definition. */
    @Override
    public void navigate(boolean requestFocus) {
        PsiFile file = getContainingFile();
        VirtualFile virtualFile = file == null ? null : file.getVirtualFile();
        if (virtualFile == null) return;
        new OpenFileDescriptor(file.getProject(), virtualFile, getTextOffset()).navigate(requestFocus);
    }

    /** Equal if it stands for the same variable (same function and name). */
    @Override
    public boolean equals(Object o) {
        return o instanceof AhkLocalVariable other && other.local.equals(local);
    }

    /** Hash of the variable. */
    @Override
    public int hashCode() {
        return local.hashCode();
    }
}
