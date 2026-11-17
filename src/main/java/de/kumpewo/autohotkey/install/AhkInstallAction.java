package de.kumpewo.autohotkey.install;

import com.intellij.openapi.actionSystem.ActionUpdateThread;
import com.intellij.openapi.actionSystem.AnActionEvent;
import com.intellij.openapi.actionSystem.CommonDataKeys;
import com.intellij.openapi.project.DumbAwareAction;
import com.intellij.openapi.project.Project;
import de.kumpewo.autohotkey.validate.AhkValidator;
import org.jetbrains.annotations.NotNull;

/**
 * Action "Deploy to Local Target..." (Code menu, editor and project view context menus): copies the tool of the
 * script of the selected AutoHotkey run configuration, otherwise of the current AutoHotkey file, as the
 * {@code settings.ahk-plugin.ini} next to it describes ({@link AhkInstaller}).
 */
public final class AhkInstallAction extends DumbAwareAction {

    /** Visible and enabled for an AutoHotkey file on disk or with a selected AutoHotkey run configuration. */
    @Override
    public void update(@NotNull AnActionEvent event) {
        Project project = event.getProject();
        event.getPresentation().setEnabledAndVisible(project != null
                && AhkValidator.hasTarget(project, event.getData(CommonDataKeys.VIRTUAL_FILE)));
    }

    /** {@link #update} only reads data keys and the selected run configuration. */
    @Override
    public @NotNull ActionUpdateThread getActionUpdateThread() {
        return ActionUpdateThread.BGT;
    }

    /** Starts the installation (checks, then the confirmation). */
    @Override
    public void actionPerformed(@NotNull AnActionEvent event) {
        Project project = event.getProject();
        if (project != null) AhkInstaller.install(project, event.getData(CommonDataKeys.VIRTUAL_FILE));
    }
}
