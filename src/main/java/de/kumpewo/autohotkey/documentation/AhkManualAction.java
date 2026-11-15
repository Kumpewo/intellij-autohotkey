package de.kumpewo.autohotkey.documentation;

import com.intellij.openapi.actionSystem.ActionUpdateThread;
import com.intellij.openapi.actionSystem.AnActionEvent;
import com.intellij.openapi.project.DumbAwareAction;
import org.jetbrains.annotations.NotNull;

/** Menu Help &gt; "AutoHotkey Plugin Manual": opens the user manual of the plugin ({@link AhkManual}). */
public final class AhkManualAction extends DumbAwareAction {

    /** Opens the manual. */
    @Override
    public void actionPerformed(@NotNull AnActionEvent e) {
        AhkManual.open(e.getProject());
    }

    /** Always available, also without a project. */
    @Override
    public @NotNull ActionUpdateThread getActionUpdateThread() {
        return ActionUpdateThread.BGT;
    }
}
