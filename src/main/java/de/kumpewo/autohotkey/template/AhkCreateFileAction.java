package de.kumpewo.autohotkey.template;

import com.intellij.ide.actions.CreateFileFromTemplateAction;
import com.intellij.ide.actions.CreateFileFromTemplateDialog;
import com.intellij.openapi.project.DumbAware;
import com.intellij.openapi.project.Project;
import com.intellij.psi.PsiDirectory;
import de.kumpewo.autohotkey.AhkBundle;
import de.kumpewo.autohotkey.AhkIcons;
import org.jetbrains.annotations.NotNull;

/**
 * "New > AutoHotkey Script": new file from the template fileTemplates/internal/AutoHotkey Script.ahk.ft
 * ({@code #Requires AutoHotkey v2.0}, {@code #SingleInstance Force}). Text of the action in AhkBundle
 * (action.AutoHotkey.NewFile.*); the user can change the template under Settings > Editor > File and Code
 * Templates.
 */
public final class AhkCreateFileAction extends CreateFileFromTemplateAction implements DumbAware {

    /** Name of the template (internalFileTemplate in plugin.xml). */
    public static final String TEMPLATE = "AutoHotkey Script";

    /** Dialog with the file name and the only kind "AutoHotkey script". */
    @Override
    protected void buildDialog(@NotNull Project project, @NotNull PsiDirectory directory,
                               @NotNull CreateFileFromTemplateDialog.Builder builder) {
        builder.setTitle(AhkBundle.message("action.new.file.title"))
                .addKind(AhkBundle.message("action.new.file.kind"), AhkIcons.FILE, TEMPLATE);
    }

    /** Name of the undoable command. */
    @Override
    protected @NotNull String getActionName(PsiDirectory directory, @NotNull String newName, String templateName) {
        return AhkBundle.message("action.new.file.name", newName);
    }
}
