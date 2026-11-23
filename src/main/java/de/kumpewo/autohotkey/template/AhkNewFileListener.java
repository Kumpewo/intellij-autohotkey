package de.kumpewo.autohotkey.template;

import com.intellij.ide.fileTemplates.FileTemplate;
import com.intellij.ide.fileTemplates.FileTemplateManager;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.application.ModalityState;
import com.intellij.openapi.command.WriteCommandAction;
import com.intellij.openapi.editor.Document;
import com.intellij.openapi.fileEditor.FileDocumentManager;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.project.ProjectLocator;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.openapi.vfs.newvfs.BulkFileListener;
import com.intellij.openapi.vfs.newvfs.events.VFileCreateEvent;
import com.intellij.openapi.vfs.newvfs.events.VFileEvent;
import com.intellij.openapi.fileTypes.FileTypeRegistry;
import de.kumpewo.autohotkey.AhkBundle;
import de.kumpewo.autohotkey.AhkFileType;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.Properties;

/**
 * Gives a new, empty AutoHotkey file that the IDE itself has created (e.g. with "New > File" and the name
 * {@code x.ahk}) the content of the file template "AutoHotkey Script" ({@link AhkCreateFileAction#TEMPLATE}, by
 * default {@code #Requires AutoHotkey v2.0}; a version changed under Settings &gt; File and Code Templates applies).
 * <p>
 * Only local files created by the IDE ({@code !isFromRefresh()}): files that appear on disk from outside (Explorer,
 * Git, other programs) arrive through a refresh and stay as they are. Files with content (copied files, "New >
 * AutoHotkey Script") are not touched. Registered in {@code plugin.xml} as listener for {@code VFS_CHANGES}.
 */
public final class AhkNewFileListener implements BulkFileListener {

    /** Collects the new empty AutoHotkey files of the IDE and fills them after the current write action. */
    @Override
    public void after(@NotNull List<? extends VFileEvent> events) {
        List<VirtualFile> created = new ArrayList<>();
        for (VFileEvent event : events) {
            if (!(event instanceof VFileCreateEvent create) || create.isFromRefresh() || create.isDirectory()) continue;
            VirtualFile file = create.getFile();
            if (isNewEmptyAhkFile(file)) created.add(file);
        }
        if (created.isEmpty()) return;
        ApplicationManager.getApplication().invokeLater(() -> {
            for (VirtualFile file : created) fill(file);
        }, ModalityState.nonModal());
    }

    /**
     * An empty local AutoHotkey file ({@code .ahk}, {@code .ah2}, {@code .ahk2} or another name associated with the
     * file type; not in the in-memory file system of tests or other virtual systems).
     */
    static boolean isNewEmptyAhkFile(@Nullable VirtualFile file) {
        return file != null && file.isValid() && file.isInLocalFileSystem()
                && FileTypeRegistry.getInstance().isFileOfType(file, AhkFileType.INSTANCE) && file.getLength() == 0;
    }

    /** Writes the template into the file if it is still empty (undoable command of its project). */
    static void fill(@NotNull VirtualFile file) {
        if (!file.isValid() || !file.isWritable()) return;
        Project project = ProjectLocator.getInstance().guessProjectForFile(file);
        if (project == null || project.isDisposed()) return;
        Document document = FileDocumentManager.getInstance().getDocument(file);
        if (document == null || document.getTextLength() > 0) return;
        String text = templateText(project);
        if (text.isEmpty()) return;
        WriteCommandAction.runWriteCommandAction(project, AhkBundle.message("action.new.file.title"), null,
                () -> document.setText(text));
    }

    /** Text of the file template "AutoHotkey Script" with the default properties; empty if it cannot be read. */
    static @NotNull String templateText(@NotNull Project project) {
        try {
            FileTemplateManager manager = FileTemplateManager.getInstance(project);
            FileTemplate template = manager.getInternalTemplate(AhkCreateFileAction.TEMPLATE);
            Properties properties = manager.getDefaultProperties();
            return template.getText(properties);
        } catch (Exception e) {
            return "";
        }
    }
}
