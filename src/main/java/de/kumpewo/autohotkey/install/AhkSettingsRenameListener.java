package de.kumpewo.autohotkey.install;

import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.command.WriteCommandAction;
import com.intellij.openapi.editor.Document;
import com.intellij.openapi.fileEditor.FileDocumentManager;
import com.intellij.openapi.fileEditor.OpenFileDescriptor;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.ui.Messages;
import com.intellij.openapi.vfs.LocalFileSystem;
import com.intellij.openapi.vfs.ReadonlyStatusHandler;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.psi.PsiElement;
import com.intellij.psi.PsiFileSystemItem;
import com.intellij.refactoring.listeners.RefactoringElementAdapter;
import com.intellij.refactoring.listeners.RefactoringElementListener;
import com.intellij.refactoring.listeners.RefactoringElementListenerProvider;
import de.kumpewo.autohotkey.AhkBundle;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;
import java.util.function.Predicate;

/**
 * Renaming or moving a file or folder of the project in the IDE (Rename, Move, drag and drop in the Project view)
 * updates the {@code settings.ahk-v2-plugin.ini} in the project root ({@link AhkSettingsEdit#rename}).
 * <ul>
 *   <li>If the file name changes and a copy line deploys the file (or the executable compiled from a renamed script)
 *       to a folder or under the old name, a dialog asks what happens to the target: keep its name (the default),
 *       rename it too (with a line in {@code [Cleanup]} for the old copy), or change only the source and open the
 *       settings file at the target ("Edit Myself"); "Cancel" changes nothing.</li>
 *   <li>Otherwise the lines are changed at once and a dialog shows them, with "OK" and "Edit Myself" (opens the
 *       settings file), so that the settings stay in view.</li>
 * </ul>
 * The change is a command of its own (undo in the editor of the settings file). If the executable of a renamed script
 * is named by {@code ;@Ahk2Exe-ExeName} with another name, the dialog says so at the start of its text (long
 * texts scroll). Changes outside the IDE (Explorer) are
 * not noticed, nor is a path moved out of the project root.
 */
public class AhkSettingsRenameListener implements RefactoringElementListenerProvider {

    /** Button of the choice: keep the name of the target. */
    static final int KEEP = 0;
    /** Button of the choice: rename the target too. */
    static final int RENAME = 1;
    /** Button of the choice: change only the source and open the settings file. */
    static final int EDIT = 2;

    /** A listener for a file or folder of a project with a settings file, {@code null} for other elements. */
    @Override
    public @Nullable RefactoringElementListener getListener(PsiElement element) {
        if (!(element instanceof PsiFileSystemItem item)) return null;
        VirtualFile file = item.getVirtualFile();
        if (file == null || !file.isInLocalFileSystem()) return null;
        Project project = element.getProject();
        Path root = AhkProjectRoot.path(project);
        if (root == null || !AhkProjectRoot.hasSettings(root)) return null;
        String oldPath = relative(root, file);
        if (oldPath == null || AhkDeployConfig.isSettingsName(oldPath)) return null;
        boolean folder = file.isDirectory();
        return new RefactoringElementAdapter() {
            @Override
            protected void elementRenamedOrMoved(@NotNull PsiElement newElement) {
                if (!(newElement instanceof PsiFileSystemItem moved) || moved.getVirtualFile() == null) return;
                String newPath = relative(root, moved.getVirtualFile());
                if (newPath == null || newPath.equals(oldPath)) return;
                ApplicationManager.getApplication().invokeLater(() -> update(project, root, oldPath, newPath, folder),
                        project.getDisposed());
            }

            @Override
            public void undoElementMovedOrRenamed(@NotNull PsiElement newElement, @NotNull String oldQualifiedName) {
                // the settings file was changed by a command of its own, undone on its own
            }
        };
    }

    /** The path of the file relative to the root, {@code null} if it is outside or no local path. */
    private static @Nullable String relative(@NotNull Path root, @NotNull VirtualFile file) {
        try {
            Path path = file.toNioPath().toAbsolutePath().normalize();
            Path base = root.toAbsolutePath().normalize();
            if (!path.toString().toLowerCase().startsWith(base.toString().toLowerCase() + java.io.File.separator)) {
                return null;
            }
            return path.toString().substring(base.toString().length() + 1);
        } catch (UnsupportedOperationException | InvalidPathException e) {
            return null;
        }
    }

    /**
     * Updates the settings file after the rename and shows the dialogs (see class comment; call on the EDT).
     *
     * @param oldPath old path relative to the root
     * @param newPath new path relative to the root
     * @param folder  a folder was renamed or moved
     */
    static void update(@NotNull Project project, @NotNull Path root, @NotNull String oldPath, @NotNull String newPath,
                       boolean folder) {
        if (project.isDisposed()) return;
        Path file = AhkDeployConfig.file(root);
        VirtualFile settings = file == null ? null : LocalFileSystem.getInstance().refreshAndFindFileByNioFile(file);
        Document document = settings == null ? null : FileDocumentManager.getInstance().getDocument(settings);
        if (document == null) return;
        // a settings file of version 2.1 is read and changed with its old names
        boolean legacy = AhkDeployConfig.isLegacy(file);
        String name = file.getFileName().toString();
        String text = document.getText();
        Predicate<String> folderTarget = folderTargets(text, legacy);
        AhkSettingsEdit.Rename sourceOnly = AhkSettingsEdit.rename(text, oldPath, newPath, folder,
                AhkSettingsEdit.TargetMode.SOURCE_ONLY, folderTarget, legacy);
        if (sourceOnly.edits().isEmpty()) return;
        String title = AhkBundle.message("settings.rename.title");
        String note = sourceOnly.executable() ? exeNameNote(root, newPath) : "";
        if (sourceOnly.choice()) {
            AhkSettingsEdit.Rename keep = AhkSettingsEdit.rename(text, oldPath, newPath, folder,
                    AhkSettingsEdit.TargetMode.KEEP_NAME, folderTarget, legacy);
            AhkSettingsEdit.Rename renamed = AhkSettingsEdit.rename(text, oldPath, newPath, folder,
                    AhkSettingsEdit.TargetMode.RENAME, folderTarget, legacy);
            int answer = Messages.showDialog(project, AhkBundle.message("settings.rename.choice", oldPath, newPath,
                            name, lines(keep), lines(renamed), note), title,
                    new String[]{AhkBundle.message("settings.rename.keep"), AhkBundle.message("settings.rename.rename"),
                            AhkBundle.message("settings.rename.edit"), Messages.getCancelButton()},
                    KEEP, Messages.getQuestionIcon());
            AhkSettingsEdit.Rename chosen = switch (answer) {
                case KEEP -> keep;
                case RENAME -> renamed;
                case EDIT -> sourceOnly;
                default -> null;
            };
            if (chosen == null || !write(project, settings, document, chosen.text(), title)) return;
            if (answer == EDIT) open(project, settings, chosen.caret());
            return;
        }
        if (!write(project, settings, document, sourceOnly.text(), title)) return;
        int answer = Messages.showDialog(project, AhkBundle.message("settings.rename.done", oldPath, newPath,
                        name, lines(sourceOnly), note), title,
                new String[]{Messages.getOkButton(), AhkBundle.message("settings.rename.edit")}, 0,
                Messages.getInformationIcon());
        if (answer == 1) open(project, settings, sourceOnly.caret());
    }

    /**
     * Decides for a target as written whether the deployment treats it as a folder, with the {@code Destination} and
     * the variables of the settings text.
     */
    private static @NotNull Predicate<String> folderTargets(@NotNull String text, boolean legacy) {
        AhkDeployConfig config = AhkDeployConfig.parse(text, legacy);
        Function<String, String> env = AhkDeployPlan.variables();
        Path destination = null;
        if (config.destination() != null) {
            String expanded = AhkDeployPlan.expand(config.destination(), env, null, new ArrayList<>(), "");
            try {
                Path path = expanded == null ? null : Path.of(expanded);
                if (path != null && path.isAbsolute()) destination = path.normalize();
            } catch (InvalidPathException ignored) {
                // no destination: relative targets cannot be resolved
            }
        }
        Path resolved = destination;
        return target -> AhkDeployPlan.isFolderTarget(target, resolved, env);
    }

    /**
     * The note for a renamed script whose {@code ;@Ahk2Exe-ExeName} names an executable other than the one of the
     * same name next to it (compiling before the deployment would fail); empty otherwise.
     */
    private static @NotNull String exeNameNote(@NotNull Path root, @NotNull String newPath) {
        try {
            Path script = root.resolve(newPath).toAbsolutePath().normalize();
            Path compiled = AhkDeployer.compiledExe(script, AhkDeployer.readText(script));
            String name = script.getFileName().toString();
            int dot = name.lastIndexOf('.');
            Path sibling = script.resolveSibling((dot > 0 ? name.substring(0, dot) : name) + ".exe");
            if (compiled == null || compiled.toString().equalsIgnoreCase(sibling.toString())) return "";
            return AhkBundle.message("settings.rename.exe.name", name, compiled.getFileName().toString());
        } catch (java.io.IOException | RuntimeException e) {
            return "";
        }
    }

    /** Replaces the text of the settings file by a command of its own and saves it; false if it is read-only. */
    private static boolean write(@NotNull Project project, @NotNull VirtualFile settings, @NotNull Document document,
                                 @NotNull String text, @NotNull String title) {
        if (ReadonlyStatusHandler.getInstance(project).ensureFilesWritable(List.of(settings)).hasReadonlyFiles()) {
            return false;
        }
        String old = document.getText();
        // only the changed part, so that markers and the caret elsewhere stay
        int start = 0;
        while (start < old.length() && start < text.length() && old.charAt(start) == text.charAt(start)) start++;
        int oldEnd = old.length();
        int newEnd = text.length();
        while (oldEnd > start && newEnd > start && old.charAt(oldEnd - 1) == text.charAt(newEnd - 1)) {
            oldEnd--;
            newEnd--;
        }
        int from = start;
        int to = oldEnd;
        String replacement = text.substring(start, newEnd);
        WriteCommandAction.writeCommandAction(project).withName(title)
                .run(() -> document.replaceString(from, to, replacement));
        FileDocumentManager.getInstance().saveDocument(document);
        return true;
    }

    /** Opens the settings file in the editor, at the offset if it is not negative. */
    private static void open(@NotNull Project project, @NotNull VirtualFile settings, int offset) {
        (offset < 0 ? new OpenFileDescriptor(project, settings) : new OpenFileDescriptor(project, settings, offset))
                .navigate(true);
    }

    /** The changed lines for the dialog: changed, removed and added lines. */
    private static @NotNull String lines(@NotNull AhkSettingsEdit.Rename rename) {
        StringBuilder text = new StringBuilder();
        for (AhkSettingsEdit.LineEdit edit : rename.edits()) {
            if (!text.isEmpty()) text.append('\n');
            if (edit.oldText() == null) {
                text.append(AhkBundle.message("settings.rename.added", edit.section(), edit.newText()));
            } else if (edit.newText() == null) {
                text.append(AhkBundle.message("settings.rename.removed", edit.line(), edit.oldText()));
            } else {
                text.append(AhkBundle.message("settings.rename.line", edit.line(), edit.oldText(), edit.newText()));
            }
        }
        return text.toString();
    }
}
