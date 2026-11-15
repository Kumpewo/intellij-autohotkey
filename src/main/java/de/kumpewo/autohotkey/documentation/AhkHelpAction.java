package de.kumpewo.autohotkey.documentation;

import com.intellij.openapi.actionSystem.ActionUpdateThread;
import com.intellij.openapi.actionSystem.AnActionEvent;
import com.intellij.openapi.project.DumbAwareAction;
import org.jetbrains.annotations.NotNull;

/** Menu Help &gt; "AutoHotkey Help": opens the help of the AutoHotkey installation ({@link AhkExternalHelp#openDocs}). */
public final class AhkHelpAction extends DumbAwareAction {

    /** Opens the AutoHotkey help. */
    @Override
    public void actionPerformed(@NotNull AnActionEvent e) {
        AhkExternalHelp.openDocs(e.getProject());
    }

    /** Always available, also without a project. */
    @Override
    public @NotNull ActionUpdateThread getActionUpdateThread() {
        return ActionUpdateThread.BGT;
    }
}
