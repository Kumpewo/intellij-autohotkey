package de.kumpewo.autohotkey.install;

import de.kumpewo.autohotkey.include.AhkFolderVariables;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.function.Function;

/**
 * "Add to Library Project...": a file of the user library folder without a library project becomes part of an existing
 * library project ({@link AhkLibraryLayout}, Lib-src mode). The file is copied unchanged (its bytes) into the project
 * root and gets a line at the end of {@code [Install]} (the section is added if it is missing):
 * <ul>
 *   <li>{@code gui.ahk=v2\}: the target is the folder of the library file relative to the {@code Destination} of the
 *       project ({@code .} if it is the same folder); without {@code Destination}, or on another drive, the absolute
 *       folder ({@code %A_MyDocuments%} for the documents folder). The file in the library folder is then the copy of
 *       the new source.</li>
 *   <li>If the project already has a file of that name, the copy is {@code gui_added.ahk} (then
 *       {@code gui_added [1].ahk} ...) and the target is {@value AhkDeployConfig#TARGET_PLACEHOLDER}, an error until
 *       it is written by hand: the project had such a file before, so its target cannot be guessed.</li>
 * </ul>
 */
public final class AhkAddToLibraryProject {

    /**
     * What was added.
     *
     * @param copy the new source in the project
     * @param line the new line of {@code [Install]}
     */
    public record Result(@NotNull Path copy, @NotNull String line) {
    }

    private AhkAddToLibraryProject() {
    }

    /**
     * The projects for the choice, as names relative to the projects folder ({@code v2\Gui}): projects in the
     * subfolder of the library file first ({@code v2\...} for {@code <Lib>\v2\x.ahk}), each group sorted by name.
     *
     * @param folders the library name of the file without its last segment ({@code v2})
     */
    static @NotNull List<Path> sorted(@NotNull List<Path> projects, @NotNull Path projectsFolder,
                                      @NotNull List<String> folders) {
        String prefix = folders.isEmpty() ? null : (String.join("\\", folders) + "\\").toLowerCase(Locale.ROOT);
        List<Path> result = new ArrayList<>(projects);
        Comparator<Path> near = Comparator.comparing(p -> prefix == null
                || !name(projectsFolder, p).toLowerCase(Locale.ROOT).startsWith(prefix));
        result.sort(near.thenComparing(p -> name(projectsFolder, p).toLowerCase(Locale.ROOT)));
        return result;
    }

    /** The name of a project relative to the projects folder ({@code v2\Gui}). */
    static @NotNull String name(@NotNull Path projectsFolder, @NotNull Path project) {
        try {
            return projectsFolder.toAbsolutePath().normalize().relativize(project.toAbsolutePath().normalize())
                    .toString();
        } catch (IllegalArgumentException e) {
            return project.toString();
        }
    }

    /**
     * Copies the library file into the project and adds its line to the settings file.
     *
     * @param project the root of the library project (with its settings file, also one of version 2.1)
     * @param file    the file of the user library folder
     * @param env     the variables ({@link AhkDeployPlan#variables()}), to resolve {@code Destination}
     */
    static @NotNull Result add(@NotNull Path project, @NotNull Path file, @NotNull Function<String, String> env)
            throws IOException {
        Path root = project.toAbsolutePath().normalize();
        Path found = AhkDeployConfig.file(root);
        Path settings = found != null ? found : root.resolve(AhkDeployConfig.FILE_NAME);
        boolean legacy = AhkDeployConfig.isLegacy(settings);
        AhkSettingsEdit.Stored stored = AhkSettingsEdit.read(settings);
        String name = file.getFileName().toString();
        Path copy = root.resolve(name);
        String target;
        if (Files.exists(copy)) {
            copy = freeName(root, name);
            target = AhkDeployConfig.TARGET_PLACEHOLDER;
        } else {
            target = target(AhkDeployConfig.parse(stored.text(), legacy), root, file, env);
        }
        String line = copy.getFileName() + "=" + target;
        Files.copy(file, copy);
        // the library copy is read-only after a deployment, the source in the project must not be
        AhkDeployCopier.clearReadOnlyQuietly(copy);
        try {
            stored.write(settings, AhkSettingsEdit.withLine(stored.text(), "Install", line, legacy));
        } catch (IOException e) {
            Files.deleteIfExists(copy);
            throw e;
        }
        return new Result(copy, line);
    }

    /** The first free name {@code name_added.ahk}, {@code name_added [1].ahk} ... in the folder. */
    static @NotNull Path freeName(@NotNull Path folder, @NotNull String name) {
        int dot = name.lastIndexOf('.');
        String base = dot > 0 ? name.substring(0, dot) : name;
        String extension = dot > 0 ? name.substring(dot) : "";
        Path candidate = folder.resolve(base + "_added" + extension);
        for (int i = 1; Files.exists(candidate); i++) {
            candidate = folder.resolve(base + "_added [" + i + "]" + extension);
        }
        return candidate;
    }

    /**
     * The target that deploys the source to the place of the library file: its folder relative to the resolved
     * {@code Destination} ({@code .} or {@code v2\}), otherwise the absolute folder with a final {@code \}.
     */
    static @NotNull String target(@NotNull AhkDeployConfig config, @NotNull Path root, @NotNull Path file,
                                  @NotNull Function<String, String> env) {
        Path folder = file.toAbsolutePath().normalize().getParent();
        Path destination = config.destination() == null ? null
                : AhkDeployPlan.targets(config, root, env).destination();
        if (destination != null) {
            Path relative = relative(destination, folder);
            if (relative != null) return relative.toString().isEmpty() ? "." : relative + "\\";
        }
        return absolute(folder) + "\\";
    }

    /** The folder relative to the base, {@code null} on another drive. */
    private static @Nullable Path relative(@NotNull Path base, @NotNull Path folder) {
        Path a = base.toAbsolutePath().normalize();
        Path b = folder.toAbsolutePath().normalize();
        if (a.getRoot() == null || b.getRoot() == null
                || !a.getRoot().toString().equalsIgnoreCase(b.getRoot().toString())) return null;
        try {
            return a.relativize(b);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    /** The absolute folder, with {@code %A_MyDocuments%} for the documents folder ({@code %} written as {@code %%}). */
    private static @NotNull String absolute(@NotNull Path folder) {
        Path documents = AhkFolderVariables.myDocuments();
        String text = folder.toString();
        if (documents != null) {
            String docs = documents.toAbsolutePath().normalize().toString();
            if (text.equalsIgnoreCase(docs)) return "%A_MyDocuments%";
            if (text.toLowerCase(Locale.ROOT).startsWith(docs.toLowerCase(Locale.ROOT) + "\\")) {
                return "%A_MyDocuments%" + text.substring(docs.length()).replace("%", "%%");
            }
        }
        return text.replace("%", "%%");
    }
}
