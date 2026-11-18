package de.kumpewo.autohotkey.install;

import com.intellij.openapi.actionSystem.ActionUpdateThread;
import com.intellij.openapi.actionSystem.AnActionEvent;
import com.intellij.openapi.project.DumbAwareAction;
import com.intellij.openapi.project.Project;
import org.jetbrains.annotations.NotNull;

/**
 * Action "Create Settings..." (Code menu, editor and project view context menus): writes the documented default
 * {@code settings.ahk-plugin.ini} with all options commented out into the project root and opens it
 * ({@link AhkSettingsFile}).
 */
public final class AhkCreateSettingsAction extends DumbAwareAction {

    /**
     * Visible in an AutoHotkey context ({@link AhkProjectRoot#isAvailable}) as long as the project root has no
     * {@code settings.ahk-plugin.ini}; the text names the project ({@link AhkProjectRoot#update}).
     */
    @Override
    public void update(@NotNull AnActionEvent event) {
        AhkProjectRoot.update(this, event, "action.AutoHotkey.CreateSettings.target", true);
    }

    /** {@link #update} reads data keys, the selected run configuration and the project root. */
    @Override
    public @NotNull ActionUpdateThread getActionUpdateThread() {
        return ActionUpdateThread.BGT;
    }

    /** Creates the file and opens it. */
    @Override
    public void actionPerformed(@NotNull AnActionEvent event) {
        Project project = event.getProject();
        if (project != null) AhkSettingsFile.create(project);
    }
}
