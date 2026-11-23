package de.kumpewo.autohotkey.install;

import com.intellij.openapi.vfs.VirtualFile;
import de.kumpewo.autohotkey.settings.AhkSettings;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Stream;

/**
 * Library projects: each library is a project of its own below the folder of the setting "Library projects folder"
 * ({@link AhkSettings#getLibraryProjectsFolder()}), and "Deploy to Local Target..." copies it into a Lib folder. The
 * plugin finds the projects in that folder and its subfolders (a folder with a {@code settings.ahk-v2-plugin.ini} is a
 * project; below it nothing more is searched, nor in folders starting with {@code .}) and resolves their copy lines
 * ({@link AhkDeployPlan}); each target is an installed copy whose source is known. Targets inside the project itself
 * are left out. If two projects deploy the same copy, the one of the naming rule wins
 * ({@link AhkLibraryLayout#projectFolderFor}: {@code <Lib>\v2\Gui.ahk} belongs to {@code <projects>\Gui}),
 * otherwise the first by path. The lines of a copy are shifted by its banner ({@code Banner}); the shift is the
 * number of lines of the copy minus that of the source (exact as long as the source has not changed since the
 * deployment). The result is kept for a few seconds, so that the many calls of the editor (write access,
 * notification) do not read the files every time.
 */
public final class AhkLibraryProjects {

    /**
     * The source of an installed copy.
     *
     * @param project the root of the library project
     * @param file    the source file in the project
     */
    public record Source(@NotNull Path project, @NotNull Path file) {
        /** Name of the library project (its folder name). */
        public @NotNull String name() {
            Path name = project.getFileName();
            return name == null ? project.toString() : name.toString();
        }

        /**
         * The line of the source for a line of the copy (both 0-based): shifted by the lines the copy has more than
         * the source; a line of the banner gives 0.
         */
        public int sourceLine(@NotNull Path copy, int copyLine) {
            return Math.max(0, copyLine - Math.max(0, lineCount(copy) - lineCount(file)));
        }
    }

    /** Number of lines of the file (0 if it cannot be read). */
    static int lineCount(@NotNull Path file) {
        try {
            String text = AhkDeployer.readText(file);
            if (text.isEmpty()) return 0;
            int lines = 1;
            for (int i = 0; i < text.length(); i++) {
                char c = text.charAt(i);
                if (c == '\n' || (c == '\r' && (i + 1 >= text.length() || text.charAt(i + 1) != '\n'))) lines++;
            }
            return lines;
        } catch (IOException | RuntimeException e) {
            return 0;
        }
    }

    /** Copies (lower-case absolute path) of one folder, read at a time ({@link System#nanoTime()}). */
    private record Snapshot(@NotNull String folder, long time, @NotNull Map<String, Source> copies) {
    }

    /** How long a scan stays valid. */
    private static final long VALID_NANOS = 5_000_000_000L;

    private static volatile @Nullable Snapshot snapshot;

    private AhkLibraryProjects() {
    }

    /** The source of the installed copy, {@code null} if the file is no copy of a library project (or not local). */
    public static @Nullable Source sourceOf(@Nullable VirtualFile file) {
        if (file == null || file.isDirectory() || !file.isInLocalFileSystem()) return null;
        try {
            return sourceOf(file.toNioPath());
        } catch (UnsupportedOperationException | InvalidPathException e) {
            return null;
        }
    }

    /** The source of the installed copy, {@code null} if the file is no copy of a library project. */
    public static @Nullable Source sourceOf(@NotNull Path file) {
        String folder = AhkSettings.getInstance().getLibraryProjectsFolder();
        if (folder.isEmpty()) return null;
        Snapshot current = snapshot;
        long now = System.nanoTime();
        if (current == null || !current.folder().equals(folder) || now - current.time() > VALID_NANOS) {
            current = new Snapshot(folder, now, scan(folder, AhkDeployPlan.variables(),
                    AhkLibraryLayout.userLibrary()));
            snapshot = current;
        }
        return current.copies().get(key(file));
    }

    /** Forgets the last scan (after a change of the setting or a deployment). */
    public static void invalidate() {
        snapshot = null;
    }

    /** Deepest folder level below the library projects folder that is searched for projects. */
    private static final int MAX_DEPTH = 8;

    /**
     * Reads the library projects below the folder: target of every copy step (lower-case absolute path) → source.
     * Unreadable folders and files with mistakes are skipped; the valid lines of a file still count.
     *
     * @param library the user library folder, for the naming rule ({@code null} = unknown)
     */
    static @NotNull Map<String, Source> scan(@NotNull String folder, @NotNull Function<String, String> env,
                                             @Nullable Path library) {
        Map<String, Source> copies = new HashMap<>();
        Path dir;
        try {
            dir = Path.of(folder);
        } catch (InvalidPathException e) {
            return copies;
        }
        if (!dir.isAbsolute() || !Files.isDirectory(dir)) return copies;
        Path projectsFolder = dir.toAbsolutePath().normalize();
        List<Path> projects = new java.util.ArrayList<>();
        collect(projectsFolder, 0, projects);
        for (Path project : projects) {
            Path root = project.toAbsolutePath().normalize();
            try {
                Path settings = AhkDeployConfig.file(root);
                if (settings == null) continue;
                AhkDeployConfig config = AhkDeployConfig.load(settings);
                AhkDeployPlan plan = AhkDeployPlan.targets(config, root, env);
                for (AhkDeployPlan.Step step : plan.steps()) {
                    if (step.target().startsWith(root)) continue;
                    Source source = new Source(root, step.source());
                    Path named = AhkLibraryLayout.projectFolderFor(step.target(), library, projectsFolder);
                    if (named != null && key(named).equals(key(root))) {
                        copies.put(key(step.target()), source);
                    } else {
                        copies.putIfAbsent(key(step.target()), source);
                    }
                }
            } catch (IOException | RuntimeException e) {
                // an unreadable settings file: this project has no known copies
            }
        }
        return copies;
    }

    /**
     * The library projects below the folder (their roots, sorted by path); empty if the folder does not exist or is
     * no absolute path.
     */
    static @NotNull List<Path> projects(@NotNull Path folder) {
        List<Path> projects = new java.util.ArrayList<>();
        if (!folder.isAbsolute() || !Files.isDirectory(folder)) return projects;
        collect(folder.toAbsolutePath().normalize(), 0, projects);
        return projects;
    }

    /** Adds the projects in the folder and its subfolders (sorted by path; not below a project). */
    private static void collect(Path folder, int depth, List<Path> projects) {
        // also a project whose settings file is still the one of version 2.1
        if (AhkDeployConfig.file(folder) != null) {
            projects.add(folder);
            return;
        }
        if (depth >= MAX_DEPTH) return;
        List<Path> children;
        try (Stream<Path> list = Files.list(folder)) {
            children = list.filter(Files::isDirectory)
                    .filter(p -> !p.getFileName().toString().startsWith(".")).sorted().toList();
        } catch (IOException | RuntimeException e) {
            return;
        }
        for (Path child : children) collect(child, depth + 1, projects);
    }

    /** The lookup key of a path: absolute, normalized, lower case (Windows ignores case). */
    private static String key(Path file) {
        return file.toAbsolutePath().normalize().toString().toLowerCase(Locale.ROOT);
    }
}
