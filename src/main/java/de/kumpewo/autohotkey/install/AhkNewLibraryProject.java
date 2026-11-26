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
 * source {@code Gui.ahk}, for a version folder {@code Gui-v2.ahk} ({@link AhkVersionedName}; new: {@code #Requires} for
 * the version and {@code ;@SetVersion 0.1.0}; or the bytes of an existing library file), and the {@code
 * settings.ahk-v2-plugin.ini} from {@value #TEMPLATE} ({@code $install$} = the lines of {@code [Install]}: the source
 * with its target, i.e. the subfolders of the library name with a final {@code \} or {@code .}, for a version folder also
 * the file name; CR LF) with the user settings ({@link AhkUserSettings}): {@code Destination} is the user library folder
 * itself, so that a project of {@code v2\Gui} deploys {@code Gui-v2.ahk=v2\Gui.ahk} and can get further targets in other
 * library folders ({@code Gui-v1.ahk=v1\Gui.ahk}) without changing {@code Destination}. An existing folder is never
 * touched; its settings file can be created afterwards ({@link #existing}). No Git repository is created.
 */
public final class AhkNewLibraryProject {

    /** The template of the settings file of a library project. */
    static final String TEMPLATE = "/install/settings-library.ahk-v2-plugin.ini";

    /** Content of a new library source for the version folder ({@code null}: none). */
    private static @NotNull String newSource(@Nullable String version) {
        return "#Requires AutoHotkey " + AhkVersionedName.requirement(version) + "\r\n;@SetVersion 0.1.0\r\n\r\n";
    }

    /** The version folder of a library name ({@code v2} of {@code v2\Gui}), {@code null} without one. */
    private static @Nullable String version(@NotNull List<String> segments) {
        String folder = segments.size() < 2 ? null : segments.get(segments.size() - 2);
        return AhkVersionedName.isVersion(folder) ? folder : null;
    }

    /** The name of the source: {@code Gui.ahk} or, for a version folder, {@code Gui-v2.ahk}. */
    private static @NotNull String sourceName(@NotNull List<String> segments) {
        String file = segments.get(segments.size() - 1) + ".ahk";
        String version = version(segments);
        return version == null ? file : AhkVersionedName.source(file, version);
    }

    private AhkNewLibraryProject() {
    }

    /**
     * Writes the project. Fails with a message if the folder exists already.
     *
     * @param projects the folder of the library projects
     * @param segments the library name ({@link AhkLibraryLayout#parseName})
     * @param content  the content of the source, {@code null} for a new library
     */
    static @NotNull Created create(@NotNull Path projects, @NotNull List<String> segments, byte @Nullable [] content)
            throws IOException {
        Path folder = AhkLibraryLayout.projectFolder(projects, segments);
        if (Files.exists(folder)) {
            throw new IOException(AhkBundle.message("library.project.exists", folder));
        }
        Files.createDirectories(folder);
        Path source = folder.resolve(sourceName(segments));
        Files.write(source, content != null ? content
                : newSource(version(segments)).getBytes(StandardCharsets.UTF_8));
        AhkSettingsFile.Created settings = AhkSettingsFile.created(settings(segments), folder, true);
        Files.writeString(folder.resolve(AhkDeployConfig.FILE_NAME), settings.text(), StandardCharsets.UTF_8);
        return new Created(source, settings.user());
    }

    /**
     * A new project.
     *
     * @param source the source file
     * @param user   what the user settings changed, {@code null} without a user file
     */
    record Created(@NotNull Path source, @Nullable AhkUserSettings.Result user) {
    }

    /** The settings of the library project ({@code \n} as separator, not yet completed). */
    private static @NotNull String settings(@NotNull List<String> segments) {
        StringBuilder target = new StringBuilder();
        for (int i = 0; i < segments.size() - 1; i++) target.append(segments.get(i)).append('\\');
        if (version(segments) != null) target.append(segments.get(segments.size() - 1)).append(".ahk");
        if (target.isEmpty()) target.append('.');
        return filled(sourceName(segments) + "=" + target + "\n");
    }

    /**
     * The settings for an existing folder below the folder of the library projects ({@code \n} as separator, not yet
     * completed): every AutoHotkey file directly in it becomes a source; the target comes from the version in its name
     * ({@link AhkVersionedName#target}), otherwise it cannot be guessed and is still to be set
     * ({@value AhkDeployConfig#TARGET_PLACEHOLDER}).
     *
     * @param rootFiles the names of the files in the folder
     */
    static @NotNull String existing(@NotNull Collection<String> rootFiles) {
        StringBuilder install = new StringBuilder();
        for (String name : new TreeSet<>(rootFiles)) {
            // some names cannot be written as a source
            if (!AhkFileRules.isAutoHotkey(name) || name.contains("=") || name.startsWith(";")
                    || name.startsWith("[")) continue;
            String target = AhkVersionedName.target(name);
            install.append(name).append('=').append(target != null ? target : AhkDeployConfig.TARGET_PLACEHOLDER)
                    .append('\n');
        }
        return filled(install.toString());
    }

    /** The template with the lines of {@code [Install]}, all ending with {@code \n}. */
    private static @NotNull String filled(@NotNull String install) {
        return AhkSettingsExplain.template(TEMPLATE).replace("$install$\n", install);
    }
}
