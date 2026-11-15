package de.kumpewo.autohotkey.include;

import de.kumpewo.autohotkey.AhkBundle;
import com.intellij.codeInsight.hint.HintManager;
import com.intellij.icons.AllIcons;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.editor.Editor;
import com.intellij.openapi.fileEditor.FileEditorManager;
import com.intellij.psi.PsiElement;
import com.intellij.psi.impl.FakePsiElement;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import javax.swing.*;
import java.nio.file.Path;

/**
 * Target of an {@code #Include Directory}: there is no file to open, so Ctrl+B shows a warning.
 * The directory sets the search directory for the following {@code #Include} lines.
 */
public final class AhkIncludeDirectoryTarget extends FakePsiElement {

    private final PsiElement directive;
    private final Path directory;

    AhkIncludeDirectoryTarget(@NotNull PsiElement directive, @NotNull Path directory) {
        this.directive = directive;
        this.directory = directory;
    }

    /** The directory named by the directive. */
    public @NotNull Path getDirectory() {
        return directory;
    }

    /** The directive that carries the reference. */
    @Override
    public PsiElement getParent() {
        return directive;
    }

    /** The path of the directory. */
    @Override
    public @NotNull String getName() {
        return directory.toString();
    }

    /** The path of the directory. */
    @Override
    public @Nullable String getPresentableText() {
        return directory.toString();
    }

    /** Folder icon. */
    @Override
    public @Nullable Icon getIcon(boolean open) {
        return AllIcons.Nodes.Folder;
    }

    /** Navigable, so that Ctrl+B shows the warning instead of "Cannot find declaration". */
    @Override
    public boolean canNavigate() {
        return true;
    }

    /** Shows the hint "Directory, not a file: …" in the editor. */
    @Override
    public void navigate(boolean requestFocus) {
        Editor editor = FileEditorManager.getInstance(directive.getProject()).getSelectedTextEditor();
        if (editor == null) return;
        String message = AhkBundle.message("include.directory.not.file", directory.toString());
        ApplicationManager.getApplication().invokeLater(() -> {
            if (!editor.isDisposed()) HintManager.getInstance().showErrorHint(editor, message);
        });
    }
}
