package de.kumpewo.autohotkey.install;

import com.intellij.openapi.command.WriteCommandAction;
import com.intellij.openapi.fileEditor.FileEditorManager;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.ui.Messages;
import com.intellij.openapi.vfs.VfsUtil;
import com.intellij.openapi.vfs.VirtualFile;
import de.kumpewo.autohotkey.AhkBundle;
import org.jetbrains.annotations.NotNull;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;

/**
 * The default {@code settings.ahk-plugin.ini} ({@link AhkInstallConfig}): a documented file with all options commented
 * out (like the configuration files of many Linux programs; a switch shows the value that changes its default, the
 * comment above names the default), from the resource
 * {@value #TEMPLATE}; {@code {tool}} there is replaced by the project name. Created in the project root by the action
 * "Create Settings..." ({@link AhkCreateSettingsAction}) and offered by "Deploy to Local Target..." when the file is
 * missing.
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
     * Creates the default file in the project root and opens it (call on the EDT); an existing file is only opened,
     * never overwritten.
     */
    public static void create(@NotNull Project project) {
        VirtualFile dir = AhkProjectRoot.root(project);
        if (dir != null) create(project, dir);
    }

    /** Creates the default file in {@code dir} and opens it; an existing file is only opened. */
    static void create(@NotNull Project project, @NotNull VirtualFile dir) {
        String title = AhkBundle.message("settings.file.title");
        VirtualFile existing = dir.findChild(AhkInstallConfig.FILE_NAME);
        if (existing != null) {
            FileEditorManager.getInstance(project).openFile(existing, true);
            return;
        }
        String text = text(project.getName());
        VirtualFile[] created = new VirtualFile[1];
        try {
            WriteCommandAction.writeCommandAction(project).withName(title).run(() -> {
                VirtualFile target = dir.createChildData(AhkSettingsFile.class, AhkInstallConfig.FILE_NAME);
                VfsUtil.saveText(target, text);
                created[0] = target;
            });
        } catch (IOException e) {
            Messages.showErrorDialog(project, AhkBundle.message("settings.file.failed", e.getMessage()), title);
            return;
        }
        if (created[0] != null) FileEditorManager.getInstance(project).openFile(created[0], true);
    }
}
