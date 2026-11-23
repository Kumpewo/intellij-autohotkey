package de.kumpewo.autohotkey.install;

import com.intellij.openapi.actionSystem.ActionUpdateThread;
import com.intellij.openapi.actionSystem.AnActionEvent;
import com.intellij.openapi.project.DumbAwareAction;
import com.intellij.openapi.project.Project;
import org.jetbrains.annotations.NotNull;

/**
 * Action "Set Version..." (Code menu, editor and project view context menus): sets the version in every script of the
 * project with a line {@code ;@Ahk2Exe-SetVersion} and optionally commits the project and sets the tag
 * {@code v<version>} ({@link AhkSetVersion}).
 */
public final class AhkSetVersionAction extends DumbAwareAction {

    /**
     * Visible in every project with a local root; the text names the project
     * ({@link AhkProjectRoot#update}).
     */
    @Override
    public void update(@NotNull AnActionEvent event) {
        AhkProjectRoot.update(this, event, "action.AutoHotkey.SetVersion.target", false);
    }

    /** {@link #update} reads data keys, the selected run configuration and the project root. */
    @Override
    public @NotNull ActionUpdateThread getActionUpdateThread() {
        return ActionUpdateThread.BGT;
    }

    /** Opens the dialog. */
    @Override
    public void actionPerformed(@NotNull AnActionEvent event) {
        Project project = event.getProject();
        if (project != null) AhkSetVersion.run(project);
    }
}
