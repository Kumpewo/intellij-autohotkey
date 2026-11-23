package de.kumpewo.autohotkey.encoding;

import com.intellij.openapi.command.WriteCommandAction;
import com.intellij.openapi.editor.Document;
import com.intellij.openapi.fileEditor.FileDocumentManager;
import com.intellij.openapi.fileEditor.FileEditor;
import com.intellij.openapi.project.DumbAware;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.ui.Messages;
import com.intellij.openapi.util.text.StringUtil;
import com.intellij.openapi.vfs.ReadonlyStatusHandler;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.ui.EditorNotificationPanel;
import com.intellij.ui.EditorNotificationProvider;
import com.intellij.ui.EditorNotifications;
import de.kumpewo.autohotkey.AhkBundle;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import javax.swing.JComponent;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.Charset;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.function.Function;

/**
 * A notification bar above a text file that was opened as UTF-8 but is no valid UTF-8 (typically an old file in the ANSI code
 * page of Windows): "File is not UTF-8 (probably ANSI)." with the link "Convert from ANSI to UTF-8". The conversion
 * reads the bytes of the file with the ANSI code page ({@link #ansiCharset()}) and replaces the text of the document,
 * which is then saved as UTF-8 without byte order mark (the default of the IDE); Ctrl+Z undoes it in the editor.
 * <p>
 * AutoHotkey v2 also reads a file without byte order mark as UTF-8, so such a file is wrong for AutoHotkey as well.
 * Not offered for files with a byte order mark, binary files, files above {@link #MAX_LENGTH}, files not on the local
 * file system, and batch files ({@code .cmd}, {@code .bat}), which the console reads in the OEM or ANSI code page.
 */
public class AhkAnsiNotification implements EditorNotificationProvider, DumbAware {

    /** Larger files are not checked (the notification bar reads the whole file). */
    static final long MAX_LENGTH = 10L * 1024 * 1024;

    /** Extensions of files that stay in their code page. */
    private static final Set<String> EXCLUDED = Set.of("cmd", "bat");

    /** The notification bar for a file that is no valid UTF-8 but was opened as UTF-8, {@code null} for other files. */
    @Override
    public @Nullable Function<? super FileEditor, ? extends JComponent> collectNotificationData(
            @NotNull Project project, @NotNull VirtualFile file) {
        if (!isAnsiCandidate(file)) return null;
        return editor -> {
            EditorNotificationPanel panel = new EditorNotificationPanel(editor, EditorNotificationPanel.Status.Warning);
            panel.setText(AhkBundle.message("encoding.ansi.notification"));
            panel.createActionLabel(AhkBundle.message("encoding.ansi.convert"), () -> convert(project, file));
            return panel;
        };
    }

    /**
     * Should the file get the notification bar: a local text file (no batch file) opened as UTF-8, without byte order mark, not
     * empty and not too large, whose bytes are no valid UTF-8?
     */
    public static boolean isAnsiCandidate(@NotNull VirtualFile file) {
        if (file.isDirectory() || !file.isValid() || !file.isInLocalFileSystem()) return false;
        if (file.getFileType().isBinary()) return false;
        String extension = file.getExtension();
        if (extension != null && EXCLUDED.contains(extension.toLowerCase(Locale.ROOT))) return false;
        if (!StandardCharsets.UTF_8.equals(file.getCharset()) || file.getBOM() != null) return false;
        long length = file.getLength();
        if (length == 0 || length > MAX_LENGTH) return false;
        try {
            return !isValidUtf8(file.contentsToByteArray());
        } catch (IOException e) {
            return false;
        }
    }

    /** Are the bytes valid UTF-8? */
    public static boolean isValidUtf8(byte @NotNull [] bytes) {
        try {
            StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes));
            return true;
        } catch (CharacterCodingException e) {
            return false;
        }
    }

    /**
     * The ANSI code page of Windows (Java property {@code native.encoding}, e.g. windows-1252); windows-1252 if that is
     * unknown or UTF-8 (Windows with UTF-8 as code page).
     */
    public static @NotNull Charset ansiCharset() {
        String name = System.getProperty("native.encoding");
        if (name != null) {
            try {
                Charset charset = Charset.forName(name);
                if (!charset.equals(StandardCharsets.UTF_8)) return charset;
            } catch (RuntimeException ignored) {
                // unknown name: the default below
            }
        }
        return Charset.forName("windows-1252");
    }

    /** The text of the bytes read in the ANSI code page, with {@code \n} as line separator (as in a document). */
    static @NotNull String decodeAnsi(byte @NotNull [] bytes) {
        return StringUtil.convertLineSeparators(new String(bytes, ansiCharset()));
    }

    /**
     * Converts the file: replaces the text of its document with the bytes read as ANSI (one undoable command) and
     * saves it as UTF-8. Errors are shown in a dialog.
     */
    public static void convert(@NotNull Project project, @NotNull VirtualFile file) {
        String title = AhkBundle.message("encoding.ansi.command");
        Document document = FileDocumentManager.getInstance().getDocument(file);
        byte[] bytes;
        try {
            bytes = file.contentsToByteArray();
        } catch (IOException e) {
            Messages.showErrorDialog(project, AhkBundle.message("encoding.ansi.failed", e.getMessage()), title);
            return;
        }
        if (document == null) return;
        if (ReadonlyStatusHandler.getInstance(project).ensureFilesWritable(List.of(file)).hasReadonlyFiles()) return;
        String text = decodeAnsi(bytes);
        WriteCommandAction.writeCommandAction(project).withName(title).run(() -> {
            file.setCharset(StandardCharsets.UTF_8);
            document.setText(text);
        });
        FileDocumentManager.getInstance().saveDocument(document);
        EditorNotifications.getInstance(project).updateNotifications(file);
    }
}
