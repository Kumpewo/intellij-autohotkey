package de.kumpewo.autohotkey.module;

import com.intellij.icons.AllIcons;
import com.intellij.ide.projectView.PresentationData;
import com.intellij.navigation.ItemPresentation;
import com.intellij.openapi.fileEditor.OpenFileDescriptor;
import com.intellij.openapi.util.TextRange;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.psi.PsiElement;
import com.intellij.psi.PsiFile;
import com.intellij.psi.impl.FakePsiElement;
import de.kumpewo.autohotkey.AhkBundle;
import de.kumpewo.autohotkey.AhkLanguage;
import de.kumpewo.autohotkey.psi.AhkRenames;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import javax.swing.*;

/**
 * Navigation target for a name that is no named PSI element of its own (AutoHotkey v2.1 modules):
 * <ul>
 *   <li>{@link Kind#MODULE}: the name of a module in {@code #Module Name} (inside the token DIRECTIVE_ARGS);</li>
 *   <li>{@link Kind#ALIAS}: an alias in {@code #Import X as Alias} or {@code #Import X {Name as Alias}};</li>
 *   <li>{@link Kind#VARIABLE}: a global variable of a module, reached through an import ({@code X.Var},
 *       {@code #Import X {Var}}): its first assignment or {@code global} declaration in the module.</li>
 * </ul>
 * Modules and aliases can be found and renamed (AhkReferencesSearcher); variables are only navigation targets
 * (free-standing global variables stay without navigation and rename).
 */
public final class AhkModuleSymbol extends FakePsiElement {

    /** What the name stands for (see class comment). */
    public enum Kind {MODULE, ALIAS, VARIABLE}

    private final Kind kind;
    private final PsiElement leaf;
    private final TextRange range;

    /**
     * Symbol for the name at {@code rangeInLeaf} of the leaf {@code leaf} (an IDENTIFIER token, or the
     * DIRECTIVE_ARGS token of {@code #Module}).
     */
    public AhkModuleSymbol(@NotNull Kind kind, @NotNull PsiElement leaf, @NotNull TextRange rangeInLeaf) {
        this.kind = kind;
        this.leaf = leaf;
        this.range = rangeInLeaf;
    }

    /** Symbol for a whole identifier leaf. */
    public AhkModuleSymbol(@NotNull Kind kind, @NotNull PsiElement identifier) {
        this(kind, identifier, new TextRange(0, identifier.getTextLength()));
    }

    /** What the name stands for. */
    public @NotNull Kind kind() {
        return kind;
    }

    /** The leaf that contains the name. */
    public @NotNull PsiElement leaf() {
        return leaf;
    }

    /** The element that contains the leaf (the directive, the import list item ...). */
    @Override
    public PsiElement getParent() {
        return leaf.getParent();
    }

    /** The file of the name. */
    @Override
    public PsiFile getContainingFile() {
        return leaf.getContainingFile();
    }

    /** AutoHotkey: Find Usages looks up its provider by the language. */
    @Override
    public @NotNull com.intellij.lang.Language getLanguage() {
        return AhkLanguage.INSTANCE;
    }

    /** The name as written. */
    @Override
    public @NotNull String getName() {
        return range.substring(leaf.getText());
    }

    /** Rename: replaces the name in its leaf. */
    @Override
    public PsiElement setName(@NotNull String name) {
        AhkRenames.replaceRange(leaf, range, name);
        return this;
    }

    /** Offset of the name (for sorting and navigation). */
    @Override
    public int getTextOffset() {
        return leaf.getTextRange().getStartOffset() + range.getStartOffset();
    }

    /** Range of the name in the file. */
    @Override
    public TextRange getTextRange() {
        return range.shiftRight(leaf.getTextRange().getStartOffset());
    }

    /** The leaf itself, e.g. for "show usages" or the navigation of the platform. */
    @Override
    public @NotNull PsiElement getNavigationElement() {
        return leaf;
    }

    /** Module, alias or variable icon. */
    @Override
    public @Nullable Icon getIcon(boolean open) {
        return switch (kind) {
            case MODULE -> AllIcons.Nodes.Module;
            case ALIAS -> AllIcons.Nodes.Alias;
            case VARIABLE -> AllIcons.Nodes.Variable;
        };
    }

    /** The name and its kind as location ("module", "import alias", "global variable"). */
    @Override
    public @NotNull ItemPresentation getPresentation() {
        return new PresentationData(getName(), AhkBundle.message("module.symbol." + kind.name().toLowerCase(java.util.Locale.ROOT)),
                getIcon(false), null);
    }

    /** Navigable while the file exists. */
    @Override
    public boolean canNavigate() {
        return leaf.isValid();
    }

    /** Opens the file at the name. */
    @Override
    public void navigate(boolean requestFocus) {
        PsiFile file = getContainingFile();
        VirtualFile virtualFile = file == null ? null : file.getVirtualFile();
        if (virtualFile == null) return;
        new OpenFileDescriptor(file.getProject(), virtualFile, getTextOffset()).navigate(requestFocus);
    }

    /** Equal if it stands for the same name in the same leaf. */
    @Override
    public boolean equals(Object o) {
        return o instanceof AhkModuleSymbol other && other.kind == kind && other.leaf.equals(leaf)
                && other.range.equals(range);
    }

    /** Hash of the leaf and the range. */
    @Override
    public int hashCode() {
        return leaf.hashCode() * 31 + range.hashCode();
    }
}
