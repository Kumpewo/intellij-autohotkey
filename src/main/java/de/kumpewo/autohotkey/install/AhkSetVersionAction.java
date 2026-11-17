package de.kumpewo.autohotkey.install;

import com.intellij.openapi.actionSystem.ActionUpdateThread;
import com.intellij.openapi.actionSystem.AnActionEvent;
import com.intellij.openapi.actionSystem.CommonDataKeys;
import com.intellij.openapi.project.DumbAwareAction;
import com.intellij.openapi.project.Project;
import de.kumpewo.autohotkey.validate.AhkValidator;
import org.jetbrains.annotations.NotNull;

/**
 * Action "Set Version..." (Code menu, editor and project view context menus): changes the line
 * {@code ;@Ahk2Exe-SetVersion} of the script of the selected AutoHotkey run configuration, otherwise of the current
 * AutoHotkey file, and optionally commits and tags it ({@link AhkSetVersion}).
 */
public final class AhkSetVersionAction extends DumbAwareAction {

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

    /** Opens the dialog. */
    @Override
    public void actionPerformed(@NotNull AnActionEvent event) {
        Project project = event.getProject();
        if (project != null) AhkSetVersion.run(project, event.getData(CommonDataKeys.VIRTUAL_FILE));
    }
}
