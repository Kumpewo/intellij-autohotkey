package de.kumpewo.autohotkey.install;

import com.intellij.openapi.actionSystem.AnAction;
import com.intellij.openapi.actionSystem.AnActionEvent;
import com.intellij.openapi.actionSystem.CommonDataKeys;
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
import de.kumpewo.autohotkey.validate.AhkValidator;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;

/**
 * The project root of "Deploy to Local Target...", "Set Version..." and "Create Settings...": one tool = one project,
 * so these actions always work on the whole project, with the one {@code settings.ahk-plugin.ini} in the project root
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

    /** The {@code settings.ahk-plugin.ini} in the project root, {@code null} if it does not exist. */
    public static @Nullable VirtualFile settings(@NotNull Project project) {
        VirtualFile root = root(project);
        VirtualFile file = root == null ? null : root.findChild(AhkInstallConfig.FILE_NAME);
        return file != null && !file.isDirectory() ? file : null;
    }

    /** Is there a {@code settings.ahk-plugin.ini} in the folder? */
    static boolean hasSettings(@NotNull Path root) {
        return Files.isRegularFile(root.resolve(AhkInstallConfig.FILE_NAME));
    }

    /**
     * Should the project actions be offered? Only in an AutoHotkey project, so that they do not clutter the menus of
     * other projects: with a {@code settings.ahk-plugin.ini} in the root, an AutoHotkey file as {@code file}, a
     * selected AutoHotkey run configuration, or at least one AutoHotkey file in the project (no file has to be open).
     */
    public static boolean isAvailable(@NotNull Project project, @Nullable VirtualFile file) {
        if (root(project) == null) return false;
        return settings(project) != null || AhkValidator.hasTarget(project, file) || containsAhkFiles(project);
    }

    /**
     * Does the project contain an AutoHotkey file (file type index, project scope)? False while the indexes are
     * being built.
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
     * Updates the presentation of a project action: visible if {@link #isAvailable} (and, with
     * {@code onlyWithoutSettings}, while the root has no {@code settings.ahk-plugin.ini}); the text of the bundle key
     * {@code textKey} names the project ({@code Deploy 'Name' to Local Target...}), the description adds the root.
     */
    public static void update(@NotNull AnAction action, @NotNull AnActionEvent event, @NotNull String textKey,
                              boolean onlyWithoutSettings) {
        Presentation presentation = event.getPresentation();
        Project project = event.getProject();
        VirtualFile root = project == null ? null : root(project);
        boolean visible = root != null && isAvailable(project, event.getData(CommonDataKeys.VIRTUAL_FILE))
                && !(onlyWithoutSettings && settings(project) != null);
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
