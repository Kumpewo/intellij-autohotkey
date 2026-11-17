package de.kumpewo.autohotkey.install;

import com.intellij.openapi.actionSystem.ActionUpdateThread;
import com.intellij.openapi.actionSystem.AnActionEvent;
import com.intellij.openapi.actionSystem.CommonDataKeys;
import com.intellij.openapi.project.DumbAwareAction;
import com.intellij.openapi.project.Project;
import de.kumpewo.autohotkey.validate.AhkValidator;
import org.jetbrains.annotations.NotNull;

/**
 * Action "Create settings.ahk-plugin.ini" (Code menu, editor and project view context menus): writes the documented
 * default file with all options commented out next to the script of the selected AutoHotkey run configuration,
 * otherwise next to the current AutoHotkey file ({@link AhkSettingsFile}); asks before overwriting an existing one.
 */
public final class AhkCreateSettingsAction extends DumbAwareAction {

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

    /** Creates the file (after confirmation if it exists) and opens it. */
    @Override
    public void actionPerformed(@NotNull AnActionEvent event) {
        Project project = event.getProject();
        if (project != null) AhkSettingsFile.create(project, event.getData(CommonDataKeys.VIRTUAL_FILE));
    }
}
