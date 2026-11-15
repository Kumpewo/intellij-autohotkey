package de.kumpewo.autohotkey.navigation;

import de.kumpewo.autohotkey.AhkBundle;
import com.intellij.icons.AllIcons;
import com.intellij.openapi.actionSystem.ActionManager;
import com.intellij.openapi.actionSystem.ActionPlaces;
import com.intellij.openapi.actionSystem.AnAction;
import com.intellij.openapi.actionSystem.IdeActions;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.editor.Editor;
import com.intellij.openapi.fileEditor.FileEditorManager;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.psi.PsiElement;
import com.intellij.psi.PsiFile;
import com.intellij.psi.impl.FakePsiElement;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import javax.swing.*;

/**
 * Navigation target for built-in names without an own definition ("built-in names navigate to the help"):
 * Ctrl+B/Ctrl+Click puts the caret on the name and opens the quick documentation (like Ctrl+Q).
 */
public final class AhkHelpTarget extends FakePsiElement {

    private final String name;
    private final PsiElement host;
    private final int offset;

    AhkHelpTarget(@NotNull String name, @NotNull PsiElement host, int offset) {
        this.name = name;
        this.host = host;
        this.offset = offset;
    }

    /** The element that carries the reference. */
    @Override
    public PsiElement getParent() {
        return host;
    }

    /** The built-in name. */
    @Override
    public @NotNull String getName() {
        return name;
    }

    /** Text in choosers. */
    @Override
    public @Nullable String getPresentableText() {
        return AhkBundle.message("navigation.help.target", name);
    }

    /** Help icon. */
    @Override
    public @Nullable Icon getIcon(boolean open) {
        return AllIcons.Actions.Help;
    }

    /** Always navigable. */
    @Override
    public boolean canNavigate() {
        return true;
    }

    /** Moves the caret to the name and runs the action "Quick Documentation". */
    @Override
    public void navigate(boolean requestFocus) {
        PsiFile file = host.getContainingFile();
        VirtualFile virtualFile = file == null ? null : file.getVirtualFile();
        if (virtualFile == null) return;
        Editor editor = FileEditorManager.getInstance(host.getProject()).getSelectedTextEditor();
        if (editor == null) return;
        ApplicationManager.getApplication().invokeLater(() -> {
            if (editor.isDisposed()) return;
            editor.getCaretModel().moveToOffset(offset);
            AnAction action = ActionManager.getInstance().getAction(IdeActions.ACTION_QUICK_JAVADOC);
            if (action != null) {
                ActionManager.getInstance().tryToExecute(action, null, editor.getContentComponent(),
                        ActionPlaces.UNKNOWN, true);
            }
        });
    }

    /** Two help targets are equal if they have the same name (case-insensitive). */
    @Override
    public boolean isEquivalentTo(PsiElement another) {
        return another instanceof AhkHelpTarget other && other.name.equalsIgnoreCase(name);
    }
}
