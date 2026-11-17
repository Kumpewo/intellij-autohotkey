package de.kumpewo.autohotkey.install;

import com.intellij.openapi.command.WriteCommandAction;
import com.intellij.openapi.editor.Document;
import com.intellij.openapi.fileEditor.FileDocumentManager;
import com.intellij.openapi.fileEditor.FileEditorManager;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.ui.Messages;
import com.intellij.openapi.vfs.VfsUtil;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.openapi.util.io.FileUtilRt;
import de.kumpewo.autohotkey.AhkBundle;
import de.kumpewo.autohotkey.validate.AhkValidator;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;

/**
 * The default {@code settings.ahk-plugin.ini} ({@link AhkInstallConfig}): a documented file with all options commented
 * out (like the configuration files of many Linux programs; a switch shows the value that changes its default, the
 * comment above names the default), from the resource
 * {@value #TEMPLATE}; {@code {tool}} there is replaced by the name of the script without extension. Created by the
 * action "Create settings.ahk-plugin.ini" ({@link AhkCreateSettingsAction}) and offered by "Deploy to Local Target..."
 * when the file is missing.
 */
public final class AhkSettingsFile {

    /** Resource with the default file. */
    static final String TEMPLATE = "/install/settings.ahk-plugin.ini";

    private AhkSettingsFile() {
    }

    /** The default file for the tool (CR LF line ends, as usual for INI files on Windows). */
    public static @NotNull String text(@NotNull String tool) {
        try (InputStream in = AhkSettingsFile.class.getResourceAsStream(TEMPLATE)) {
            if (in == null) throw new IllegalStateException("missing resource " + TEMPLATE);
            String text = new String(in.readAllBytes(), StandardCharsets.UTF_8);
            return text.replace("\r\n", "\n").replace("{tool}", tool).replace("\n", "\r\n");
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /**
     * Creates the default file next to the target script of {@code file} (as for Validate/Compile) and opens it (call
     * on the EDT). If the file exists, asks first whether to overwrite it.
     */
    public static void create(@NotNull Project project, @Nullable VirtualFile file) {
        if (project.isDisposed()) return;
        AhkValidator.Target target = AhkValidator.target(project, file);
        if (target == null) return;
        create(project, target.script(), true);
    }

    /**
     * Creates the default file next to {@code script} and opens it; with {@code askOverwrite} an existing file is
     * only replaced after confirmation, otherwise it is left as it is.
     */
    static void create(@NotNull Project project, @NotNull VirtualFile script, boolean askOverwrite) {
        VirtualFile dir = script.getParent();
        if (dir == null) return;
        String title = AhkBundle.message("settings.file.title");
        VirtualFile existing = dir.findChild(AhkInstallConfig.FILE_NAME);
        if (existing != null) {
            if (!askOverwrite || Messages.showYesNoDialog(project,
                    AhkBundle.message("settings.file.exists", AhkInstallConfig.FILE_NAME, dir.getPresentableUrl()),
                    title, AhkBundle.message("settings.file.overwrite"), Messages.getCancelButton(),
                    Messages.getWarningIcon()) != Messages.YES) {
                return;
            }
        }
        String text = text(FileUtilRt.getNameWithoutExtension(script.getName()));
        VirtualFile[] created = new VirtualFile[1];
        try {
            WriteCommandAction.writeCommandAction(project).withName(title).run(() -> {
                VirtualFile target = existing != null ? existing : dir.createChildData(AhkSettingsFile.class,
                        AhkInstallConfig.FILE_NAME);
                Document document = FileDocumentManager.getInstance().getCachedDocument(target);
                if (document != null) {
                    document.setText(text.replace("\r\n", "\n"));
                    FileDocumentManager.getInstance().saveDocument(document);
                } else {
                    VfsUtil.saveText(target, text);
                }
                created[0] = target;
            });
        } catch (IOException e) {
            Messages.showErrorDialog(project, AhkBundle.message("settings.file.failed", e.getMessage()), title);
            return;
        }
        if (created[0] != null) FileEditorManager.getInstance(project).openFile(created[0], true);
    }
}
