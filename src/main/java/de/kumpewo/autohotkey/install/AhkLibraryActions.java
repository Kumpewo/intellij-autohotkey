package de.kumpewo.autohotkey.install;

import com.intellij.ide.fileTemplates.FileTemplateManager;
import com.intellij.openapi.actionSystem.ActionUpdateThread;
import com.intellij.openapi.actionSystem.AnAction;
import com.intellij.openapi.actionSystem.AnActionEvent;
import com.intellij.openapi.actionSystem.CommonDataKeys;
import com.intellij.openapi.fileEditor.OpenFileDescriptor;
import com.intellij.openapi.project.DumbAware;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.ui.Messages;
import com.intellij.openapi.vfs.LocalFileSystem;
import com.intellij.openapi.vfs.VirtualFile;
import de.kumpewo.autohotkey.AhkBundle;
import de.kumpewo.autohotkey.template.AhkCreateFileAction;
import de.kumpewo.autohotkey.validate.AhkTargetPresentation;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.util.List;

/**
 * The actions that create libraries ({@link AhkLibraryLayout}):
 * <ul>
 *   <li>{@link NewFile} "File > New > AutoHotkey Library File": a file in the user library folder (content of the file
 *       template "AutoHotkey Script"); in Lib-src mode the dialog warns and offers to create a library project
 *       instead.</li>
 *   <li>{@link NewProject} "File > New > AutoHotkey Library Project..." (also on the welcome screen, only in Lib-src
 *       mode): a new library project, opened in a window of its own.</li>
 *   <li>{@link FromFile} "Create Library Project 'Gui'" (context menus, where "Open Library Project" is, and the
 *       banner above the file, only in Lib-src mode): the project of a library file that has none yet, with a copy of the file as source; the file
 *       in the library folder stays and is a copy from then on.</li>
 *   <li>{@link AddToProject} "Add to Library Project..." (same places): the file becomes a source of an existing
 *       library project chosen from a list ({@link AhkAddToLibraryProject}).</li>
 * </ul>
 */
public final class AhkLibraryActions {

    private AhkLibraryActions() {
    }

    /** Creates the project and opens it with its source; errors are shown in a dialog. */
    static void createProject(@Nullable Project current, @NotNull List<String> segments, byte @Nullable [] content) {
        Path projects = AhkLibraryLayout.projectsFolder();
        if (projects == null) return;
        Path source;
        try {
            source = AhkNewLibraryProject.create(projects, segments, content);
        } catch (IOException | InvalidPathException e) {
            Messages.showErrorDialog(current, AhkBundle.message("library.project.failed", e.getMessage()),
                    AhkBundle.message("library.project.title"));
            return;
        }
        AhkLibraryCopyNotification.refreshAll();
        AhkOpenLibraryProjectAction.open(current,
                new AhkLibraryProjects.Source(AhkLibraryLayout.projectFolder(projects, segments), source), 0);
    }

    /** "AutoHotkey Library File": a file in the user library folder. */
    public static final class NewFile extends AnAction implements DumbAware {

        /** Needs no background thread. */
        @Override
        public @NotNull ActionUpdateThread getActionUpdateThread() {
            return ActionUpdateThread.BGT;
        }

        /** Visible in a project if the user library folder is known. */
        @Override
        public void update(@NotNull AnActionEvent e) {
            e.getPresentation().setEnabledAndVisible(e.getProject() != null && AhkLibraryLayout.userLibrary() != null);
        }

        /** Asks for the name, creates the file (or a library project) and opens it. */
        @Override
        public void actionPerformed(@NotNull AnActionEvent e) {
            Project project = e.getProject();
            Path library = AhkLibraryLayout.userLibrary();
            if (project == null || library == null) return;
            boolean source = AhkLibraryLayout.isLibrarySourceMode();
            AhkLibraryNameDialog dialog = new AhkLibraryNameDialog(project, AhkBundle.message("library.file.title"),
                    AhkBundle.message("library.file.hint", library),
                    AhkBundle.message(source ? "library.project.create" : "library.file.create"),
                    source ? AhkBundle.message("library.file.warning") : null);
            if (!dialog.showAndGet() || dialog.segments() == null) return;
            List<String> segments = dialog.segments();
            if (!dialog.createsFile()) {
                createProject(project, segments, null);
                return;
            }
            Path file = AhkLibraryLayout.libraryFile(library, segments);
            try {
                if (Files.exists(file)) throw new IOException(AhkBundle.message("library.file.exists", file));
                Files.createDirectories(file.getParent());
                String text = FileTemplateManager.getInstance(project).getInternalTemplate(AhkCreateFileAction.TEMPLATE)
                        .getText(FileTemplateManager.getInstance(project).getDefaultProperties());
                Files.writeString(file, text.replace("\r\n", "\n").replace("\n", "\r\n"), StandardCharsets.UTF_8);
            } catch (IOException | RuntimeException ex) {
                Messages.showErrorDialog(project, AhkBundle.message("library.file.failed", ex.getMessage()),
                        AhkBundle.message("library.file.title"));
                return;
            }
            VirtualFile created = LocalFileSystem.getInstance().refreshAndFindFileByNioFile(file);
            if (created != null) new OpenFileDescriptor(project, created).navigate(true);
        }
    }

    /** "AutoHotkey Library Project...": a new library project. */
    public static final class NewProject extends AnAction implements DumbAware {

        /** Reads the settings only. */
        @Override
        public @NotNull ActionUpdateThread getActionUpdateThread() {
            return ActionUpdateThread.BGT;
        }

        /** Only in Lib-src mode. */
        @Override
        public void update(@NotNull AnActionEvent e) {
            e.getPresentation().setEnabledAndVisible(AhkLibraryLayout.isLibrarySourceMode());
        }

        /** Asks for the name and creates the project. */
        @Override
        public void actionPerformed(@NotNull AnActionEvent e) {
            Path projects = AhkLibraryLayout.projectsFolder();
            if (projects == null) return;
            AhkLibraryNameDialog dialog = new AhkLibraryNameDialog(e.getProject(),
                    AhkBundle.message("library.project.title"), AhkBundle.message("library.project.hint", projects),
                    AhkBundle.message("library.project.create"), null);
            if (dialog.showAndGet() && dialog.segments() != null) createProject(e.getProject(), dialog.segments(), null);
        }
    }

    /** "Add to Library Project...": the file becomes a source of an existing library project. */
    public static final class AddToProject extends AnAction implements DumbAware {

        /** Reads the file system: in the background. */
        @Override
        public @NotNull ActionUpdateThread getActionUpdateThread() {
            return ActionUpdateThread.BGT;
        }

        /** Visible where "Create Library Project" is ({@link FromFile#segments}). */
        @Override
        public void update(@NotNull AnActionEvent e) {
            e.getPresentation().setEnabledAndVisible(FromFile.segments(e.getData(CommonDataKeys.VIRTUAL_FILE)) != null);
        }

        /** Shows the list of the library projects. */
        @Override
        public void actionPerformed(@NotNull AnActionEvent e) {
            choose(e.getProject(), e.getData(CommonDataKeys.VIRTUAL_FILE));
        }

        /**
         * Shows the library projects in a popup list (typing filters it; projects in the subfolder of the file first)
         * and adds the file to the chosen one; also the link of the banner above a library file without a project.
         */
        static void choose(@Nullable Project project, @Nullable VirtualFile file) {
            List<String> segments = FromFile.segments(file);
            Path projects = AhkLibraryLayout.projectsFolder();
            if (segments == null || projects == null) return;
            List<Path> roots = AhkAddToLibraryProject.sorted(AhkLibraryProjects.projects(projects), projects,
                    segments.subList(0, segments.size() - 1));
            String title = AhkBundle.message("library.add.title");
            if (roots.isEmpty()) {
                Messages.showInfoMessage(project, AhkBundle.message("library.add.none", projects), title);
                return;
            }
            List<String> names = roots.stream().map(p -> AhkAddToLibraryProject.name(projects, p)).toList();
            com.intellij.openapi.ui.popup.JBPopup popup = com.intellij.openapi.ui.popup.JBPopupFactory.getInstance()
                    .createPopupChooserBuilder(names)
                    .setTitle(title)
                    .setNamerForFiltering(name -> name)
                    .setItemChosenCallback(name -> add(project, file, roots.get(names.indexOf(name))))
                    .createPopup();
            if (project != null) {
                popup.showCenteredInCurrentWindow(project);
            } else {
                popup.showInFocusCenter();
            }
        }

        /** Adds the file to the project and opens the project at the new source; errors are shown in a dialog. */
        static void add(@Nullable Project project, @NotNull VirtualFile file, @NotNull Path root) {
            AhkAddToLibraryProject.Result result;
            try {
                result = AhkAddToLibraryProject.add(root, file.toNioPath(), AhkDeployPlan.variables());
            } catch (IOException | RuntimeException ex) {
                Messages.showErrorDialog(project, AhkBundle.message("library.add.failed", ex.getMessage()),
                        AhkBundle.message("library.add.title"));
                return;
            }
            LocalFileSystem.getInstance().refreshAndFindFileByNioFile(root.resolve(AhkDeployConfig.FILE_NAME));
            AhkLibraryCopyNotification.refreshAll();
            AhkOpenLibraryProjectAction.open(project, new AhkLibraryProjects.Source(root, result.copy()), 0);
        }
    }

    /**
     * "Create Library Project 'Gui'" (for {@code <Lib>\v2\Gui.ahk}; the name of the project folder): the project of a
     * library file that has none.
     */
    public static final class FromFile extends AnAction implements DumbAware {

        /** Reads the file system: in the background. */
        @Override
        public @NotNull ActionUpdateThread getActionUpdateThread() {
            return ActionUpdateThread.BGT;
        }

        /** Visible on a file of the user library folder without a library project, in Lib-src mode. */
        @Override
        public void update(@NotNull AnActionEvent e) {
            List<String> segments = segments(e.getData(CommonDataKeys.VIRTUAL_FILE));
            e.getPresentation().setEnabledAndVisible(segments != null);
            if (segments != null) {
                e.getPresentation().setText(AhkBundle.message("action.AutoHotkey.CreateLibraryProject.target",
                        AhkTargetPresentation.shorten(segments.get(segments.size() - 1))), false);
            }
        }

        /** Copies the file into a new library project and opens it. */
        @Override
        public void actionPerformed(@NotNull AnActionEvent e) {
            create(e.getProject(), e.getData(CommonDataKeys.VIRTUAL_FILE));
        }

        /**
         * Copies the file into a new library project and opens it; also the link of the banner above a library file
         * without a project ({@link AhkLibraryCopyNotification}). Does nothing if the action does not apply.
         */
        static void create(@Nullable Project project, @Nullable VirtualFile file) {
            List<String> segments = segments(file);
            if (segments == null) return;
            byte[] content;
            try {
                content = Files.readAllBytes(file.toNioPath());
            } catch (IOException | RuntimeException ex) {
                Messages.showErrorDialog(project, AhkBundle.message("library.project.failed", ex.getMessage()),
                        AhkBundle.message("library.project.title"));
                return;
            }
            createProject(project, segments, content);
        }

        /**
         * The library name of the file, {@code null} if the action does not apply to it: not in Lib-src mode, no
         * {@code .ahk} file of the user library folder, or a known copy of a library project.
         */
        static @Nullable List<String> segments(@Nullable VirtualFile file) {
            if (file == null || file.isDirectory() || !file.isInLocalFileSystem()) return null;
            if (!AhkLibraryLayout.isLibrarySourceMode() || AhkLibraryProjects.sourceOf(file) != null) return null;
            if (!"ahk".equalsIgnoreCase(file.getExtension())) return null;
            try {
                return AhkLibraryLayout.nameOf(file.toNioPath(), AhkLibraryLayout.userLibrary());
            } catch (UnsupportedOperationException | InvalidPathException ex) {
                return null;
            }
        }
    }
}
