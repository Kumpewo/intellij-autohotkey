package de.kumpewo.autohotkey.install;

import com.intellij.openapi.actionSystem.AnAction;
import com.intellij.openapi.actionSystem.AnActionEvent;
import com.intellij.openapi.actionSystem.Presentation;
import com.intellij.openapi.project.DumbService;
import com.intellij.openapi.project.IndexNotReadyException;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.project.ProjectUtil;
import com.intellij.psi.search.FileTypeIndex;
import com.intellij.psi.search.GlobalSearchScope;
import com.intellij.openapi.vfs.VirtualFile;
import de.kumpewo.autohotkey.AhkBundle;
import de.kumpewo.autohotkey.AhkFileType;
import de.kumpewo.autohotkey.validate.AhkTargetPresentation;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.nio.file.InvalidPathException;
import java.nio.file.Path;

/**
 * The project root of "Deploy to Local Target...", "Set Version..." and "Create Settings...": one tool = one project,
 * so these actions always work on the whole project, with the one {@code settings.ahk-v2-plugin.ini} in the project root
 * ({@link ProjectUtil#guessProjectDir}, the folder that was opened as the project).
 */
public final class AhkProjectRoot {

    private AhkProjectRoot() {
    }

    /** The project root, {@code null} if the project has none on the local file system. */
    public static @Nullable VirtualFile root(@NotNull Project project) {
        if (project.isDisposed()) return null;
        VirtualFile dir = ProjectUtil.guessProjectDir(project);
        return dir != null && dir.isDirectory() && dir.isInLocalFileSystem() ? dir : null;
    }

    /** The project root as a path, {@code null} without one. */
    public static @Nullable Path path(@NotNull Project project) {
        VirtualFile root = root(project);
        if (root == null) return null;
        try {
            return root.toNioPath();
        } catch (UnsupportedOperationException | InvalidPathException e) {
            return null;
        }
    }

    /**
     * The settings file in the project root ({@code settings.ahk-v2-plugin.ini}, otherwise the
     * {@code settings.ahk-plugin.ini} of version 2.1), {@code null} if there is none.
     */
    public static @Nullable VirtualFile settings(@NotNull Project project) {
        VirtualFile root = root(project);
        if (root == null) return null;
        for (String name : new String[]{AhkDeployConfig.FILE_NAME, AhkDeployConfig.LEGACY_FILE_NAME}) {
            VirtualFile file = root.findChild(name);
            if (file != null && !file.isDirectory()) return file;
        }
        return null;
    }

    /** Is there a settings file in the folder (also one of version 2.1)? */
    static boolean hasSettings(@NotNull Path root) {
        return AhkDeployConfig.file(root) != null;
    }

    /**
     * Does the project contain an AutoHotkey file (file type index, project scope)? False while the indexes are
     * being built. Decides whether a new {@code settings.ahk-v2-plugin.ini} shows the examples of an AutoHotkey tool.
     */
    static boolean containsAhkFiles(@NotNull Project project) {
        if (project.isDisposed() || DumbService.isDumb(project)) return false;
        try {
            return FileTypeIndex.containsFileOfType(AhkFileType.INSTANCE, GlobalSearchScope.projectScope(project));
        } catch (IndexNotReadyException e) {
            return false;
        }
    }

    /**
     * Updates the presentation of a project action: visible in every project with a local root, also without
     * AutoHotkey files, because deploying and setting versions work for tools in any language (with
     * {@code onlyWithoutSettings} only while the root has no {@code settings.ahk-v2-plugin.ini}); the text of the bundle key
     * {@code textKey} names the project ({@code Deploy 'Name' to Local Target...}), the description adds the root.
     */
    public static void update(@NotNull AnAction action, @NotNull AnActionEvent event, @NotNull String textKey,
                              boolean onlyWithoutSettings) {
        Presentation presentation = event.getPresentation();
        Project project = event.getProject();
        VirtualFile root = project == null ? null : root(project);
        boolean visible = root != null && !(onlyWithoutSettings && settings(project) != null);
        presentation.setEnabledAndVisible(visible);
        if (!visible) return;
        // no mnemonic: names often contain '_' or '&'
        presentation.setText(AhkBundle.message(textKey, AhkTargetPresentation.shorten(project.getName())), false);
        String base = action.getTemplatePresentation().getDescription();
        String where = AhkBundle.message("action.target.project", root.getPresentableUrl());
        presentation.setDescription(base == null || base.isEmpty() ? where
                : AhkBundle.message("action.target.description", base, where));
    }
}
