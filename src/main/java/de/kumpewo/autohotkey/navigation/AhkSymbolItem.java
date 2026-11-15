package de.kumpewo.autohotkey.navigation;

import com.intellij.navigation.ItemPresentation;
import com.intellij.navigation.PsiElementNavigationItem;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.roots.ProjectFileIndex;
import com.intellij.openapi.vfs.VfsUtilCore;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.pom.Navigatable;
import com.intellij.psi.PsiElement;
import com.intellij.psi.PsiFile;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import javax.swing.*;

/**
 * Entry of Go to Symbol / Go to Class: the qualified text ({@code Outer.Method()}, {@code F1::}) with the file as
 * location (relative to its content root if possible). A wrapper instead of the PSI element itself, because
 * the element's own presentation (used in Ctrl+B choosers) names only the enclosing class, not the file.
 */
public final class AhkSymbolItem implements PsiElementNavigationItem, ItemPresentation {

    private final AhkSymbols.Symbol symbol;

    AhkSymbolItem(@NotNull AhkSymbols.Symbol symbol) {
        this.symbol = symbol;
    }

    /** The symbol behind the entry. */
    public @NotNull AhkSymbols.Symbol symbol() {
        return symbol;
    }

    /** The element to navigate to (also used by the platform for actions on the entry). */
    @Override
    public @NotNull PsiElement getTargetElement() {
        return symbol.target();
    }

    /** The name that is matched against the typed pattern (and stored in the index). */
    @Override
    public @NotNull String getName() {
        return symbol.name();
    }

    /** Text, file and icon. */
    @Override
    public @NotNull ItemPresentation getPresentation() {
        return this;
    }

    /** {@code Outer.Method()}, {@code Class.field}, {@code Label:}, {@code F1::}. */
    @Override
    public @NotNull String getPresentableText() {
        return symbol.text();
    }

    /**
     * The file of the symbol: relative to its content root ({@code sub\a.ahk}), otherwise (AutoHotkey Lib) the full
     * path.
     */
    @Override
    public @Nullable String getLocationString() {
        PsiFile file = symbol.target().getContainingFile();
        VirtualFile virtualFile = file == null ? null : file.getVirtualFile();
        if (virtualFile == null) return file == null ? null : file.getName();
        Project project = file.getProject();
        VirtualFile root = ProjectFileIndex.getInstance(project).getContentRootForFile(virtualFile);
        String relative = root == null ? null : VfsUtilCore.getRelativePath(virtualFile, root, '\\');
        return relative != null ? relative : virtualFile.getPresentableUrl();
    }

    /** Icon of the kind. */
    @Override
    public @NotNull Icon getIcon(boolean unused) {
        return symbol.kind().icon();
    }

    /** Opens the file at the name. */
    @Override
    public void navigate(boolean requestFocus) {
        if (symbol.target() instanceof Navigatable navigatable) navigatable.navigate(requestFocus);
    }

    /** Navigable while the element exists. */
    @Override
    public boolean canNavigate() {
        return symbol.target().isValid() && symbol.target() instanceof Navigatable navigatable && navigatable.canNavigate();
    }

    /** Same as {@link #canNavigate()}. */
    @Override
    public boolean canNavigateToSource() {
        return canNavigate();
    }

    /** Equal if it stands for the same element and kind. */
    @Override
    public boolean equals(Object o) {
        return o instanceof AhkSymbolItem other && other.symbol.kind() == symbol.kind()
                && other.symbol.target().equals(symbol.target());
    }

    /** Hash of the element. */
    @Override
    public int hashCode() {
        return symbol.target().hashCode();
    }

    /** For debugging. */
    @Override
    public String toString() {
        return symbol.text();
    }
}
