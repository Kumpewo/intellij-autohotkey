package de.kumpewo.autohotkey.validate;

import com.intellij.openapi.actionSystem.ActionUpdateThread;
import com.intellij.openapi.actionSystem.AnActionEvent;
import com.intellij.openapi.actionSystem.CommonDataKeys;
import com.intellij.openapi.project.DumbAwareAction;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.vfs.VirtualFile;
import org.jetbrains.annotations.NotNull;

/**
 * Action "Validate with AutoHotkey" (Code menu, editor and project view context menus, Alt+Shift+V): validates
 * the script of the selected AutoHotkey run configuration, otherwise the current AutoHotkey file, with
 * {@code AutoHotkey /validate} (AhkValidator).
 */
public class AhkValidateAction extends DumbAwareAction {

    /**
     * Visible and enabled for an AutoHotkey file on disk or with a selected AutoHotkey run configuration; the text
     * names the target ({@link AhkTargetPresentation}).
     */
    @Override
    public void update(@NotNull AnActionEvent event) {
        AhkTargetPresentation.update(this, event, "action.AutoHotkey.Validate.target");
    }

    /** {@link #update} reads data keys, the selected run configuration and the {@code #Include} chain. */
    @Override
    public @NotNull ActionUpdateThread getActionUpdateThread() {
        return ActionUpdateThread.BGT;
    }

    /** Starts the validation and always shows the console. */
    @Override
    public void actionPerformed(@NotNull AnActionEvent event) {
        Project project = event.getProject();
        VirtualFile file = event.getData(CommonDataKeys.VIRTUAL_FILE);
        if (project != null) AhkValidator.validate(project, file, true);
    }
}
