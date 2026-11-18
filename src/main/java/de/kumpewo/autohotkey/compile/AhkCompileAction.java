package de.kumpewo.autohotkey.compile;

import com.intellij.openapi.actionSystem.ActionUpdateThread;
import com.intellij.openapi.actionSystem.AnActionEvent;
import com.intellij.openapi.actionSystem.CommonDataKeys;
import com.intellij.openapi.project.DumbAwareAction;
import com.intellij.openapi.project.Project;
import de.kumpewo.autohotkey.validate.AhkTargetPresentation;
import org.jetbrains.annotations.NotNull;

/**
 * Action "Compile with Ahk2Exe" (Code menu, editor and project view context menus): compiles the script of the
 * selected AutoHotkey run configuration, otherwise the current AutoHotkey file, into an executable
 * ({@link AhkCompiler}).
 */
public class AhkCompileAction extends DumbAwareAction {

    /**
     * Visible and enabled for an AutoHotkey file on disk or with a selected AutoHotkey run configuration; the text
     * names the target ({@link AhkTargetPresentation}).
     */
    @Override
    public void update(@NotNull AnActionEvent event) {
        AhkTargetPresentation.update(this, event, "action.AutoHotkey.Compile.target");
    }

    /** {@link #update} reads data keys, the selected run configuration and the {@code #Include} chain. */
    @Override
    public @NotNull ActionUpdateThread getActionUpdateThread() {
        return ActionUpdateThread.BGT;
    }

    /** Starts the compilation. */
    @Override
    public void actionPerformed(@NotNull AnActionEvent event) {
        Project project = event.getProject();
        if (project != null) {
            AhkCompiler.compile(project, event.getData(CommonDataKeys.VIRTUAL_FILE),
                    event.getData(CommonDataKeys.EDITOR));
        }
    }
}
