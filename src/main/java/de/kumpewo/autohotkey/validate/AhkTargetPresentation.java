package de.kumpewo.autohotkey.validate;

import com.intellij.openapi.actionSystem.AnAction;
import com.intellij.openapi.actionSystem.AnActionEvent;
import com.intellij.openapi.actionSystem.CommonDataKeys;
import com.intellij.openapi.actionSystem.Presentation;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.roots.ProjectFileIndex;
import com.intellij.openapi.vfs.VfsUtilCore;
import com.intellij.openapi.vfs.VirtualFile;
import de.kumpewo.autohotkey.AhkBundle;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.io.File;

/**
 * Menu text and description of the actions that work on the target of {@link AhkValidator#target} (Validate and
 * Compile; the project actions Deploy, Set Version and Create Settings use {@code AhkProjectRoot}): the text names
 * the target, e.g. {@code Compile 'Main' with Ahk2Exe} with the name of the selected run configuration or of the file, so it is
 * clear what the action applies to; the description adds the path of the script, relative to the project root.
 */
public final class AhkTargetPresentation {

    /** Longer names are cut to {@code MAX_NAME - 1} characters and an ellipsis. */
    static final int MAX_NAME = 40;

    private AhkTargetPresentation() {
    }

    /**
     * Updates the presentation of {@code action}: hidden without a target, otherwise visible with the text of the
     * bundle key {@code textKey} (one parameter, the name of the target) and the static description of the action
     * followed by the script of the target.
     */
    public static void update(@NotNull AnAction action, @NotNull AnActionEvent event, @NotNull String textKey) {
        Presentation presentation = event.getPresentation();
        Project project = event.getProject();
        VirtualFile file = event.getData(CommonDataKeys.VIRTUAL_FILE);
        AhkValidator.Target target = project == null || !AhkValidator.hasTarget(project, file)
                ? null : AhkValidator.target(project, file);
        presentation.setEnabledAndVisible(target != null);
        if (target == null) return;
        // no mnemonic: names often contain '_' or '&'
        presentation.setText(AhkBundle.message(textKey, shorten(name(target))), false);
        presentation.setDescription(description(project, action.getTemplatePresentation().getDescription(), target));
    }

    /** Name shown in the menu: the run configuration, otherwise the file name of the script. */
    static @NotNull String name(@NotNull AhkValidator.Target target) {
        return target.configuration() != null ? target.configuration() : target.script().getName();
    }

    /** {@code name}, cut to {@link #MAX_NAME} characters including a trailing ellipsis if it is longer. */
    public static @NotNull String shorten(@NotNull String name) {
        return name.length() <= MAX_NAME ? name : name.substring(0, MAX_NAME - 1) + "…";
    }

    /**
     * Path of {@code script} for the description, relative to its content root (the project root), so the relevant
     * part stays visible in the status bar; the full path for a script outside the project.
     */
    public static @NotNull String path(@NotNull Project project, @NotNull VirtualFile script) {
        VirtualFile root = ProjectFileIndex.getInstance(project).getContentRootForFile(script);
        String relative = root == null ? null : VfsUtilCore.getRelativePath(script, root, File.separatorChar);
        return relative == null || relative.isEmpty() ? script.getPresentableUrl() : relative;
    }

    /** The static description of the action followed by the full name and the path of the script of the target. */
    static @NotNull String description(@NotNull Project project, @Nullable String base,
                                       @NotNull AhkValidator.Target target) {
        String path = path(project, target.script());
        String targetText = target.configuration() != null
                ? AhkBundle.message("action.target.configuration", target.configuration(), path)
                : AhkBundle.message("action.target.script", path);
        return base == null || base.isEmpty() ? targetText
                : AhkBundle.message("action.target.description", base, targetText);
    }
}
