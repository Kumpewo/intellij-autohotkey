package de.kumpewo.autohotkey.install;

import de.kumpewo.autohotkey.AhkBundle;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collection;
import java.util.List;
import java.util.TreeSet;

/**
 * Creates a library project ({@link AhkLibraryLayout}): the folder {@code <projects>\Gui} (for {@code v2\Gui}), the
 * source {@code Gui.ahk} (new: {@code #Requires AutoHotkey v2.0} and {@code ;@SetVersion 0.1.0}; or the bytes
 * of an existing library file) and the {@code settings.ahk-v2-plugin.ini} from {@value #TEMPLATE} ({@code $project$} =
 * name of the project folder, {@code $install$} = the lines of {@code [Install]}: the
 * source with its target, i.e. the subfolders of the library name with a final {@code \} or {@code .}; not in braces,
 * which the placeholders of {@code Banner} use; CR LF): {@code Destination} is always the user library folder itself,
 * so that a project of {@code v2\Gui} deploys {@code Gui.ahk=v2\} and can get further targets in other library folders
 * ({@code Gui-v1.ahk=Gui.ahk}) without changing {@code Destination}; {@code Banner} is
 * {@code find:}{@value #SHARED_BANNER} if the projects folder or a folder above it contains that file
 * ({@link AhkBanner#find}), otherwise {@code @library}, and {@code [SetVersion]} includes all AutoHotkey files. An
 * existing folder is never touched; its settings file can be created afterwards ({@link #existing}). No Git repository
 * is created.
 */
public final class AhkNewLibraryProject {

    /** The template of the settings file of a library project. */
    static final String TEMPLATE = "/install/settings-library.ahk-v2-plugin.ini";

    /** Content of a new library source. */
    static final String NEW_SOURCE = "#Requires AutoHotkey v2.0\r\n;@SetVersion 0.1.0\r\n\r\n";

    private AhkNewLibraryProject() {
    }

    /**
     * Writes the project; returns the source file. Fails with a message if the folder exists already.
     *
     * @param projects the folder of the library projects
     * @param segments the library name ({@link AhkLibraryLayout#parseName})
     * @param content  the content of the source, {@code null} for a new library
     */
    static @NotNull Path create(@NotNull Path projects, @NotNull List<String> segments, byte @Nullable [] content)
            throws IOException {
        Path folder = AhkLibraryLayout.projectFolder(projects, segments);
        if (Files.exists(folder)) {
            throw new IOException(AhkBundle.message("library.project.exists", folder));
        }
        String file = segments.get(segments.size() - 1) + ".ahk";
        Files.createDirectories(folder);
        Path source = folder.resolve(file);
        Files.write(source, content != null ? content : NEW_SOURCE.getBytes(StandardCharsets.UTF_8));
        boolean sharedBanner = AhkBanner.find(projects, SHARED_BANNER) != null;
        Files.writeString(folder.resolve(AhkDeployConfig.FILE_NAME), settings(segments, sharedBanner),
                StandardCharsets.UTF_8);
        return source;
    }

    /**
     * The name of the banner shared by several projects, found by {@code find:} in the project folder or a folder
     * above it (for example the folder of the library projects).
     */
    static final String SHARED_BANNER = "banner.txt";

    /**
     * The settings file of the library project (CR LF). {@code Banner} is {@code find:} of the
     * {@link #SHARED_BANNER} if {@code sharedBanner}, otherwise {@code @library}; the other line stays as a comment
     * ({@code $library-banner$}/{@code $file-banner$} in the template become {@code ;} or nothing).
     */
    static @NotNull String settings(@NotNull List<String> segments, boolean sharedBanner) {
        StringBuilder target = new StringBuilder();
        for (int i = 0; i < segments.size() - 1; i++) target.append(segments.get(i)).append('\\');
        if (target.isEmpty()) target.append('.');
        String project = segments.get(segments.size() - 1);
        return settings(project, project + ".ahk=" + target + "\n", sharedBanner);
    }

    /**
     * The settings file for an existing folder below the folder of the library projects: every AutoHotkey file directly
     * in it becomes a source whose target is still to be set ({@value AhkDeployConfig#TARGET_PLACEHOLDER}), because it
     * cannot be guessed; without such a file the line is only an example.
     *
     * @param project   the folder name
     * @param rootFiles the names of the files in the folder
     */
    static @NotNull String existing(@NotNull String project, @NotNull Collection<String> rootFiles,
                                    boolean sharedBanner) {
        StringBuilder install = new StringBuilder();
        for (String name : new TreeSet<>(rootFiles)) {
            // some names cannot be written as a source
            if (!AhkFileRules.isAutoHotkey(name) || name.contains("=") || name.startsWith(";")
                    || name.startsWith("[")) continue;
            install.append(name).append('=').append(AhkDeployConfig.TARGET_PLACEHOLDER).append('\n');
        }
        if (install.isEmpty()) install.append(';').append(project).append(".ahk=.\n");
        return settings(project, install.toString(), sharedBanner);
    }

    /** The template with the lines of {@code [Install]}, all ending with {@code \n}. */
    private static @NotNull String settings(@NotNull String project, @NotNull String install, boolean sharedBanner) {
        String text = AhkSettingsExplain.template(TEMPLATE).replace("$project$", project)
                .replace("$install$\n", install).replace("$banner$\n", ";*.cmd=yes\n")
                .replace("$library-banner$", sharedBanner ? ";" : "").replace("$file-banner$", sharedBanner ? "" : ";");
        return AhkSettingsFile.complete(text, true, project);
    }
}
