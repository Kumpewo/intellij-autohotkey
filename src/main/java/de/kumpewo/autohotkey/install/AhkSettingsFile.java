package de.kumpewo.autohotkey.install;

import com.intellij.openapi.command.WriteCommandAction;
import com.intellij.openapi.editor.Document;
import com.intellij.openapi.fileEditor.FileDocumentManager;
import com.intellij.openapi.fileEditor.FileEditorManager;
import com.intellij.openapi.fileEditor.OpenFileDescriptor;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.ui.Messages;
import com.intellij.openapi.vfs.VfsUtil;
import com.intellij.openapi.vfs.VirtualFile;
import de.kumpewo.autohotkey.AhkBundle;
import de.kumpewo.autohotkey.compile.AhkCompiler;
import de.kumpewo.autohotkey.settings.AhkSettings;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/**
 * The default {@code settings.ahk-v2-plugin.ini} ({@link AhkDeployConfig}): a short file with the options of the resource
 * {@value #TEMPLATE} (most of them commented out; a switch shows the value that changes its default), the user settings
 * ({@link AhkUserSettings}) and the explanations of {@link AhkSettingsExplain#restore} unless the settings page turns
 * them off. {@code $install$} in the resource becomes the line of the main script. Created in the project root by the
 * action "Create Settings..." ({@link AhkCreateSettingsAction}) and offered by "Deploy to Local Target..." when the file
 * is missing.
 */
public final class AhkSettingsFile {

    /** Resource with the default file. */
    static final String TEMPLATE = "/install/settings.ahk-v2-plugin.ini";

    private AhkSettingsFile() {
    }

    /**
     * The default file of a project (CR LF line ends, as usual for INI files on Windows), without user settings.
     *
     * @param main the line of the main script ({@link #mainLine}), {@code null} = none
     */
    static @NotNull String text(@Nullable String main) {
        return complete(filled(main), false);
    }

    /** The template with the line of the main script ({@code \n} as separator). */
    private static @NotNull String filled(@Nullable String main) {
        return AhkSettingsExplain.template(TEMPLATE).replace("$install$\n", main == null ? "" : main + "\n");
    }

    /**
     * The line of {@code [Install]} for the main script {@code <folder name>.ahk} in the project root: its executable if
     * the script has a head for Ahk2Exe ({@link AhkCompiler#hasBaseDirective(CharSequence)}), otherwise the script
     * itself; {@code null} if there is no such script. Nothing else is deployed without being asked for.
     */
    static @Nullable String mainLine(@NotNull VirtualFile dir) {
        for (VirtualFile child : dir.getChildren()) {
            String name = child.getName();
            if (child.isDirectory() || !name.equalsIgnoreCase(dir.getName() + ".ahk")) continue;
            // some names cannot be written as a source
            if (name.contains("=") || name.startsWith(";") || name.startsWith("[")) return null;
            Document document = FileDocumentManager.getInstance().getDocument(child);
            boolean compiled = document != null && AhkCompiler.hasBaseDirective(document.getCharsSequence());
            return (compiled ? child.getNameWithoutExtension() + ".exe" : name) + "=.";
        }
        return null;
    }

    /**
     * The text of a new file (CR LF): with the explanations, unless the settings page turns them off.
     *
     * @param text the filled template ({@code \n} as separator)
     */
    static @NotNull String complete(@NotNull String text, boolean library) {
        if (AhkSettings.getInstance().isExplainNewSettings()) text = AhkSettingsExplain.restore(text, library);
        return text.replace("\n", "\r\n");
    }

    /** A new file. */
    record Created(@NotNull String text, @Nullable AhkUserSettings.Result user) {
    }

    /**
     * A new file in the folder: the filled template with the user settings of the folder ({@link AhkUserSettings#apply}),
     * then completed.
     */
    static @NotNull Created created(@NotNull String filled, @Nullable java.nio.file.Path folder, boolean library) {
        AhkUserSettings.Result user = AhkUserSettings.apply(filled, folder, library);
        return new Created(complete(user == null ? filled : user.text(), library), user);
    }

    /**
     * Creates the default file in the project root and opens it (call on the EDT); an existing file is only opened,
     * never overwritten.
     */
    public static void create(@NotNull Project project) {
        create(project, (String) null);
    }

    /**
     * Creates the default file in the project root and opens it, with the caret at the header {@code [section]}
     * ({@code null}: at the start); an existing file is only opened (call on the EDT).
     */
    public static void create(@NotNull Project project, @Nullable String section) {
        VirtualFile dir = AhkProjectRoot.root(project);
        if (dir != null) create(project, dir, section);
    }

    /**
     * Creates the default file in {@code dir} and opens it; an existing file (also the {@code settings.ahk-v2-plugin.ini}
     * of version 2.1) is only opened. Below the folder of the library projects ({@link AhkLibraryProjects#isBelow}) the file is the one of a library project
     * ({@link AhkNewLibraryProject#existing}).
     */
    static void create(@NotNull Project project, @NotNull VirtualFile dir) {
        create(project, dir, null);
    }

    /** Like {@link #create(Project, VirtualFile)}, then the caret goes to the header {@code [section]} if given. */
    static void create(@NotNull Project project, @NotNull VirtualFile dir, @Nullable String section) {
        String title = AhkBundle.message("settings.file.title");
        VirtualFile existing = dir.findChild(AhkDeployConfig.FILE_NAME);
        if (existing == null) existing = dir.findChild(AhkDeployConfig.LEGACY_FILE_NAME);
        if (existing != null) {
            open(project, existing, section);
            return;
        }
        java.nio.file.Path path = dir.getFileSystem().getNioPath(dir);
        boolean library = path != null && AhkLibraryProjects.isBelow(path, AhkLibraryLayout.projectsFolder());
        List<String> rootFiles = new ArrayList<>();
        for (VirtualFile child : dir.getChildren()) {
            if (!child.isDirectory()) rootFiles.add(child.getName());
        }
        VirtualFile[] created = new VirtualFile[1];
        AhkUserSettings.Result user;
        try {
            Created file = created(library ? AhkNewLibraryProject.existing(rootFiles) : filled(mainLine(dir)), path,
                    library);
            user = file.user();
            WriteCommandAction.writeCommandAction(project).withName(title).run(() -> {
                VirtualFile target = dir.createChildData(AhkSettingsFile.class, AhkDeployConfig.FILE_NAME);
                VfsUtil.saveText(target, file.text());
                created[0] = target;
            });
        } catch (IOException e) {
            Messages.showErrorDialog(project, AhkBundle.message("settings.file.failed", e.getMessage()), title);
            return;
        }
        if (created[0] != null) open(project, created[0], section);
        if (user != null) AhkUserSettings.notify(project, user, false);
    }

    /** Opens the settings file, with the caret at the header {@code [section]} if given and present. */
    static void open(@NotNull Project project, @NotNull VirtualFile file, @Nullable String section) {
        Document document = section == null ? null : FileDocumentManager.getInstance().getDocument(file);
        int line = document == null ? -1 : AhkSetVersion.sectionLine(document.getText(), section);
        if (line < 0) {
            FileEditorManager.getInstance(project).openFile(file, true);
        } else {
            new OpenFileDescriptor(project, file, line, 0).navigate(true);
        }
    }
}
