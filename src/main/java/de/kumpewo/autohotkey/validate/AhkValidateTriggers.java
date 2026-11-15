package de.kumpewo.autohotkey.validate;

import com.intellij.openapi.actionSystem.ActionManager;
import com.intellij.openapi.actionSystem.AnAction;
import com.intellij.openapi.actionSystem.AnActionEvent;
import com.intellij.openapi.actionSystem.AnActionResult;
import com.intellij.openapi.actionSystem.CommonDataKeys;
import com.intellij.openapi.actionSystem.IdeActions;
import com.intellij.openapi.actionSystem.ex.AnActionListener;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.editor.Document;
import com.intellij.openapi.fileEditor.FileDocumentManager;
import com.intellij.openapi.fileEditor.FileDocumentManagerListener;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.project.ProjectLocator;
import com.intellij.openapi.vfs.VirtualFile;
import de.kumpewo.autohotkey.settings.AhkSettings;
import org.jetbrains.annotations.NotNull;

/**
 * Automatic validation with AutoHotkey according to the setting "Validate with AutoHotkey"
 * ({@link AhkSettings.ValidateTrigger}): after "Reformat Code" on an AutoHotkey file, or after an AutoHotkey file has
 * been saved. As with the action, the script of the selected AutoHotkey run configuration is validated if there is
 * one (AhkValidator#target). Independent of the setting, "Reformat Code" on an AutoHotkey file empties the console of
 * the last validation. Registered twice in {@code plugin.xml} (as listener for both topics).
 */
public class AhkValidateTriggers implements FileDocumentManagerListener, AnActionListener {

    /** Setting "on save": validates the saved file (not when the validator saves it itself). */
    @Override
    public void afterDocumentSaved(@NotNull Document document) {
        if (AhkSettings.getInstance().getValidateTrigger() != AhkSettings.ValidateTrigger.ON_SAVE) return;
        if (AhkValidator.isSaving()) return;
        VirtualFile file = FileDocumentManager.getInstance().getFile(document);
        if (!AhkValidator.canValidate(file)) return;
        Project project = ProjectLocator.getInstance().guessProjectForFile(file);
        if (project != null) later(project, file);
    }

    /**
     * After the action "Reformat Code" on an AutoHotkey file: empties the console of the last validation (its output
     * belongs to the code before the change, whatever the setting) and, with the setting "after Reformat Code",
     * validates the file of the editor.
     */
    @Override
    public void afterActionPerformed(@NotNull AnAction action, @NotNull AnActionEvent event,
                                     @NotNull AnActionResult result) {
        if (!IdeActions.ACTION_EDITOR_REFORMAT.equals(ActionManager.getInstance().getId(action))) return;
        Project project = event.getProject();
        VirtualFile file = event.getData(CommonDataKeys.VIRTUAL_FILE);
        if (project == null || !AhkValidator.canValidate(file)) return;
        AhkValidator.clearConsole(project);
        if (AhkSettings.getInstance().getValidateTrigger() == AhkSettings.ValidateTrigger.AFTER_REFORMAT) {
            later(project, file);
        }
    }

    /** Starts the validation after the current command (saving, reformatting) has finished. */
    private static void later(@NotNull Project project, @NotNull VirtualFile file) {
        ApplicationManager.getApplication().invokeLater(() -> AhkValidator.validate(project, file, false),
                project.getDisposed());
    }
}
