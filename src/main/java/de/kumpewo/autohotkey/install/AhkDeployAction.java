package de.kumpewo.autohotkey.install;

import com.intellij.openapi.actionSystem.ActionUpdateThread;
import com.intellij.openapi.actionSystem.AnActionEvent;
import com.intellij.openapi.project.DumbAwareAction;
import com.intellij.openapi.project.Project;
import org.jetbrains.annotations.NotNull;

/**
 * Action "Deploy to Local Target..." (Code menu, editor and project view context menus): copies the tool of the
 * project as the {@code settings.ahk-v2-plugin.ini} in the project root describes ({@link AhkDeployer}).
 */
public final class AhkDeployAction extends DumbAwareAction {

    /**
     * Visible in every project with a local root; the text names the project
     * ({@link AhkProjectRoot#update}).
     */
    @Override
    public void update(@NotNull AnActionEvent event) {
        AhkProjectRoot.update(this, event, "action.AutoHotkey.Install.target", false);
    }

    /** {@link #update} reads data keys, the selected run configuration and the project root. */
    @Override
    public @NotNull ActionUpdateThread getActionUpdateThread() {
        return ActionUpdateThread.BGT;
    }

    /** Checks the settings and opens the confirmation (or offers to create the settings). */
    @Override
    public void actionPerformed(@NotNull AnActionEvent event) {
        Project project = event.getProject();
        if (project != null) AhkDeployer.deploy(project);
    }
}
