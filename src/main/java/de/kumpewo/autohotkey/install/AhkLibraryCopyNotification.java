package de.kumpewo.autohotkey.install;

import com.intellij.ide.util.PropertiesComponent;
import com.intellij.openapi.fileEditor.FileEditor;
import com.intellij.openapi.fileEditor.TextEditor;
import com.intellij.openapi.options.ShowSettingsUtil;
import com.intellij.openapi.project.DumbAware;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.project.ProjectManager;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.ui.EditorNotificationPanel;
import com.intellij.ui.EditorNotificationProvider;
import com.intellij.ui.EditorNotifications;
import de.kumpewo.autohotkey.AhkBundle;
import de.kumpewo.autohotkey.settings.AhkSettingsConfigurable;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import javax.swing.JComponent;
import java.nio.file.InvalidPathException;
import java.util.function.Function;

/**
 * Banners above the files of the user library folder in Lib-src mode:
 * <ul>
 *   <li>an installed copy of a library project ({@link AhkLibraryProjects}): "Installed copy of library project
 *       'Json' - edit the source and deploy." with the link "Open Library Project", which opens the source at the
 *       line of the caret ({@link AhkOpenLibraryProjectAction#open});</li>
 *   <li>a library file without a project: "Library file without a library project." with the links "Create Library
 *       Project" ({@link AhkLibraryActions.FromFile#create}) and "Add to Library Project..."
 *       ({@link AhkLibraryActions.AddToProject#choose}).</li>
 * </ul>
 * In Lib-direct mode (no library projects folder set) a {@code .ahk} file of the user library folder gets a hint
 * that library projects exist, with the links "Set Library Projects Folder..." (the settings page) and "Don't Show
 * Again" (stored application-wide under {@link #HINT_DISMISSED}).
 */
public class AhkLibraryCopyNotification implements EditorNotificationProvider, DumbAware {

    /** Application-wide flag: the hint about library projects in Lib-direct mode is not shown any more. */
    static final String HINT_DISMISSED = "AutoHotkey.libraryProjectsHint.dismissed";

    /** The notification bar for a copy or a library file without a project, {@code null} for other files. */
    @Override
    public @Nullable Function<? super FileEditor, ? extends JComponent> collectNotificationData(
            @NotNull Project project, @NotNull VirtualFile file) {
        AhkLibraryProjects.Source source = AhkLibraryProjects.sourceOf(file);
        if (source == null) {
            if (needsProjectsHint(file)) return editor -> projectsHint(editor, project);
            if (AhkLibraryActions.FromFile.segments(file) == null) return null;
            return editor -> {
                EditorNotificationPanel panel = new EditorNotificationPanel(editor, EditorNotificationPanel.Status.Info);
                panel.setText(AhkBundle.message("library.none.notification"));
                panel.createActionLabel(AhkBundle.message("library.project.create"),
                        () -> AhkLibraryActions.FromFile.create(project, file));
                panel.createActionLabel(AhkBundle.message("library.add.link"),
                        () -> AhkLibraryActions.AddToProject.choose(project, file));
                return panel;
            };
        }
        return editor -> {
            EditorNotificationPanel panel = new EditorNotificationPanel(editor, EditorNotificationPanel.Status.Info);
            panel.setText(AhkBundle.message("library.copy.notification", source.name()));
            panel.createActionLabel(AhkBundle.message("library.open.link"), () -> {
                int line = editor instanceof TextEditor text
                        ? text.getEditor().getCaretModel().getLogicalPosition().line : 0;
                AhkOpenLibraryProjectAction.open(project, source, source.sourceLine(file.toNioPath(), line));
            });
            return panel;
        };
    }

    /**
     * Does the file get the hint about library projects: Lib-direct mode, a {@code .ahk} file of the user library
     * folder, and the hint was not dismissed?
     */
    static boolean needsProjectsHint(@NotNull VirtualFile file) {
        if (file.isDirectory() || !file.isInLocalFileSystem() || AhkLibraryLayout.isLibrarySourceMode()) return false;
        if (!"ahk".equalsIgnoreCase(file.getExtension())) return false;
        if (PropertiesComponent.getInstance().getBoolean(HINT_DISMISSED)) return false;
        try {
            return AhkLibraryLayout.nameOf(file.toNioPath(), AhkLibraryLayout.userLibrary()) != null;
        } catch (UnsupportedOperationException | InvalidPathException ex) {
            return false;
        }
    }

    /** The hint about library projects with the links to the settings and to dismiss it for good. */
    private static @NotNull JComponent projectsHint(@NotNull FileEditor editor, @NotNull Project project) {
        EditorNotificationPanel panel = new EditorNotificationPanel(editor, EditorNotificationPanel.Status.Info);
        panel.setText(AhkBundle.message("library.hint.notification"));
        panel.createActionLabel(AhkBundle.message("library.hint.settings"),
                () -> ShowSettingsUtil.getInstance().showSettingsDialog(project, AhkSettingsConfigurable.class));
        panel.createActionLabel(AhkBundle.message("library.hint.dismiss"), () -> {
            PropertiesComponent.getInstance().setValue(HINT_DISMISSED, true);
            refreshAll();
        });
        return panel;
    }

    /** Reads the library projects again and updates the notification bars and the write access of all open editors. */
    public static void refreshAll() {
        AhkLibraryProjects.invalidate();
        for (Project project : ProjectManager.getInstance().getOpenProjects()) {
            if (!project.isDisposed()) EditorNotifications.getInstance(project).updateAllNotifications();
        }
    }
}
