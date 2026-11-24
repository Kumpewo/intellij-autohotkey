package de.kumpewo.autohotkey.install;

import com.intellij.openapi.application.ModalityState;
import com.intellij.openapi.command.WriteCommandAction;
import com.intellij.openapi.editor.Document;
import com.intellij.openapi.editor.Editor;
import com.intellij.openapi.editor.ScrollType;
import com.intellij.openapi.editor.event.DocumentEvent;
import com.intellij.openapi.editor.event.DocumentListener;
import com.intellij.openapi.fileEditor.FileDocumentManager;
import com.intellij.openapi.fileEditor.FileEditor;
import com.intellij.openapi.fileEditor.TextEditor;
import com.intellij.openapi.project.DumbAware;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.util.Key;
import com.intellij.openapi.util.TextRange;
import com.intellij.openapi.util.text.StringUtil;
import com.intellij.openapi.vfs.ReadonlyStatusHandler;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.ui.EditorNotificationPanel;
import com.intellij.ui.EditorNotificationProvider;
import com.intellij.ui.EditorNotifications;
import com.intellij.util.Alarm;
import de.kumpewo.autohotkey.AhkBundle;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import javax.swing.JComponent;
import java.nio.file.Path;
import java.util.List;
import java.util.function.Function;

/**
 * Banner above {@code settings.ahk-v2-plugin.ini} with the links "Restore Explanations"
 * ({@link AhkSettingsExplain#restore}) and "Remove Explanations and Compact" ({@link AhkSettingsExplain#compact}); a
 * link that would change nothing is left out. Each changes the document in one undo step. The banner follows the edited
 * text.
 */
public final class AhkSettingsNotification implements EditorNotificationProvider, DumbAware {

    /** Marks an editor whose document is already observed. */
    private static final Key<Boolean> OBSERVED = Key.create("AutoHotkey.settings.observed");

    /** Delay in ms after typing before the banner is checked again. */
    private static final int DELAY = 500;

    /** Is it a settings file with the current name? */
    static boolean isSettings(@NotNull VirtualFile file) {
        return !file.isDirectory() && file.getName().equalsIgnoreCase(AhkDeployConfig.FILE_NAME);
    }

    /** Is it the settings file of a library project (recognized like by "Create Settings...")? */
    static boolean isLibrary(@NotNull VirtualFile file) {
        Path path = file.getFileSystem().getNioPath(file);
        return path != null && path.getParent() != null
                && AhkLibraryProjects.isBelow(path.getParent(), AhkLibraryLayout.projectsFolder());
    }

    /** The banner of a settings file, {@code null} for other files. */
    @Override
    public @Nullable Function<? super FileEditor, ? extends JComponent> collectNotificationData(
            @NotNull Project project, @NotNull VirtualFile file) {
        if (!isSettings(file)) return null;
        Document document = FileDocumentManager.getInstance().getDocument(file);
        if (document == null) return null;
        String text = document.getText();
        boolean canCompact = !compact(text).equals(text);
        boolean canRestore = !restored(project, file, text).equals(text);
        return editor -> {
            observe(project, file, document, editor);
            if (!canCompact && !canRestore) return null;
            EditorNotificationPanel panel = new EditorNotificationPanel(editor, EditorNotificationPanel.Status.Info);
            panel.setText(AhkBundle.message("settings.explain.notification"));
            if (canRestore) {
                panel.createActionLabel(AhkBundle.message("settings.explain.restore"), () -> change(project, file,
                        editor, "settings.explain.restore", old -> restored(project, file, old)));
            }
            if (canCompact) {
                panel.createActionLabel(AhkBundle.message("settings.explain.compact"), () -> change(project, file,
                        editor, "settings.explain.compact", AhkSettingsNotification::compact));
            }
            return panel;
        };
    }

    /** {@link AhkSettingsExplain#compact} with the line separator of documents. */
    static @NotNull String compact(@NotNull String text) {
        return StringUtil.convertLineSeparators(AhkSettingsExplain.compact(text));
    }

    /** {@link AhkSettingsExplain#restore} with the name of "Create Settings..." and the line separator of documents. */
    static @NotNull String restored(@NotNull Project project, @NotNull VirtualFile file, @NotNull String text) {
        boolean library = isLibrary(file);
        VirtualFile folder = file.getParent();
        boolean root = !library && folder != null && folder.equals(AhkProjectRoot.root(project));
        String name = root || folder == null ? project.getName() : folder.getName();
        return StringUtil.convertLineSeparators(AhkSettingsExplain.restore(text, library, name));
    }

    /** Checks the banner again after changes of the document, as long as the editor is open. */
    private static void observe(@NotNull Project project, @NotNull VirtualFile file, @NotNull Document document,
                                @NotNull FileEditor editor) {
        if (editor.getUserData(OBSERVED) != null) return;
        editor.putUserData(OBSERVED, true);
        Alarm alarm = new Alarm(Alarm.ThreadToUse.SWING_THREAD, editor);
        document.addDocumentListener(new DocumentListener() {
            /** Restarts the delay. */
            @Override
            public void documentChanged(@NotNull DocumentEvent event) {
                alarm.cancelAllRequests();
                alarm.addRequest(() -> {
                    if (!project.isDisposed()) EditorNotifications.getInstance(project).updateNotifications(file);
                }, DELAY, ModalityState.any());
            }
        }, editor);
    }

    /**
     * Changes the document in one command; the caret stays in its line if that line remains.
     *
     * @param title bundle key of the command name
     */
    static void change(@NotNull Project project, @NotNull VirtualFile file, @Nullable FileEditor fileEditor,
                       @NotNull String title, @NotNull Function<String, String> edit) {
        Document document = FileDocumentManager.getInstance().getDocument(file);
        if (document == null) return;
        if (ReadonlyStatusHandler.getInstance(project).ensureFilesWritable(List.of(file)).hasReadonlyFiles()) return;
        String old = document.getText();
        String text = edit.apply(old);
        if (text.equals(old)) return;
        Editor editor = fileEditor instanceof TextEditor textEditor ? textEditor.getEditor() : null;
        int caret = editor == null ? 0 : newOffset(document, editor.getCaretModel().getOffset(), text);
        WriteCommandAction.writeCommandAction(project).withName(AhkBundle.message(title)).run(() -> {
            document.setText(text);
            if (editor != null) editor.getCaretModel().moveToOffset(caret);
        });
        if (editor != null) editor.getScrollingModel().scrollToCaret(ScrollType.MAKE_VISIBLE);
    }

    /**
     * The start of the caret line in the new text (counting equal lines); from an explanation or an empty line the next
     * line that remains, 0 if there is none.
     */
    static int newOffset(@NotNull Document document, int offset, @NotNull String text) {
        if (document.getTextLength() == 0) return 0;
        for (int line = document.getLineNumber(offset); line < document.getLineCount(); line++) {
            String wanted = line(document, line);
            if (wanted.isBlank() || wanted.equals(AhkSettingsHelp.MARKER)
                    || wanted.startsWith(AhkSettingsHelp.MARKER + " ")) continue;
            int count = 0;
            for (int i = 0; i <= line; i++) {
                if (line(document, i).equals(wanted)) count++;
            }
            int start = 0;
            for (String candidate : text.split("\n", -1)) {
                if (candidate.equals(wanted) && --count == 0) return start;
                start += candidate.length() + 1;
            }
        }
        return 0;
    }

    /** The text of a line. */
    private static @NotNull String line(@NotNull Document document, int line) {
        return document.getText(new TextRange(document.getLineStartOffset(line), document.getLineEndOffset(line)));
    }
}
