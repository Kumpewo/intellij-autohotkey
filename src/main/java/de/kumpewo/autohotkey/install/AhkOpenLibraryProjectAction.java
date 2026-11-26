package de.kumpewo.autohotkey.install;

import com.intellij.ide.impl.ProjectUtil;
import com.intellij.openapi.actionSystem.ActionUpdateThread;
import com.intellij.openapi.actionSystem.AnAction;
import com.intellij.openapi.actionSystem.AnActionEvent;
import com.intellij.openapi.actionSystem.CommonDataKeys;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.editor.Editor;
import com.intellij.openapi.fileEditor.FileDocumentManager;
import com.intellij.openapi.fileEditor.OpenFileDescriptor;
import com.intellij.openapi.project.DumbAware;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.ui.Messages;
import com.intellij.openapi.vfs.LocalFileSystem;
import com.intellij.openapi.vfs.VirtualFile;
import de.kumpewo.autohotkey.AhkBundle;
import de.kumpewo.autohotkey.validate.AhkTargetPresentation;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * "Open Library Project 'Json'" in the context menus of the editor and of the Project view (also below External
 * Libraries): for an installed copy of a library project ({@link AhkLibraryProjects}) opens the project in a window
 * of its own (or brings its window to the front) and there the source file, at the line of the caret minus the
 * banner lines ({@code Banner}). Only visible on such a copy.
 */
public class AhkOpenLibraryProjectAction extends AnAction implements DumbAware {

    /** Reads the file system: in the background. */
    @Override
    public @NotNull ActionUpdateThread getActionUpdateThread() {
        return ActionUpdateThread.BGT;
    }

    /** Visible with the name of the project if the selected file is an installed copy. */
    @Override
    public void update(@NotNull AnActionEvent e) {
        AhkLibraryProjects.Source source = AhkLibraryProjects.sourceOf(e.getData(CommonDataKeys.VIRTUAL_FILE));
        e.getPresentation().setEnabledAndVisible(source != null);
        if (source != null) {
            e.getPresentation().setText(AhkBundle.message("action.AutoHotkey.OpenLibraryProject.target",
                    AhkTargetPresentation.shorten(source.name())), false);
        }
    }

    /** Opens the project and the source. */
    @Override
    public void actionPerformed(@NotNull AnActionEvent e) {
        VirtualFile file = e.getData(CommonDataKeys.VIRTUAL_FILE);
        AhkLibraryProjects.Source source = AhkLibraryProjects.sourceOf(file);
        if (source == null) return;
        Editor editor = e.getData(CommonDataKeys.EDITOR);
        int line = 0;
        if (editor != null && file.equals(FileDocumentManager.getInstance().getFile(editor.getDocument()))) {
            line = source.sourceLine(file.toNioPath(), editor.getCaretModel().getLogicalPosition().line);
        }
        open(e.getProject(), source, line);
    }

    /**
     * Opens the library project in a window of its own (an open one comes to the front) and there the source at the
     * line (0-based). Returns the project, {@code null} if it was not opened. Call on the EDT.
     */
    public static @Nullable Project open(@Nullable Project current, @NotNull AhkLibraryProjects.Source source, int line) {
        Project project = ProjectUtil.findAndFocusExistingProjectForPath(source.project());
        if (project == null) project = ProjectUtil.openOrImport(source.project(), current, true);
        if (project == null) return null;
        VirtualFile file = LocalFileSystem.getInstance().refreshAndFindFileByNioFile(source.file());
        if (file == null) {
            Messages.showErrorDialog(project, AhkBundle.message("library.source.missing", source.file()),
                    AhkBundle.message("library.open.title"));
            return project;
        }
        Project target = project;
        ApplicationManager.getApplication().invokeLater(() -> {
            if (!target.isDisposed()) new OpenFileDescriptor(target, file, line, 0).navigate(true);
        });
        return project;
    }
}
