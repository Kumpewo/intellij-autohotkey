package de.kumpewo.autohotkey.install;

import de.kumpewo.autohotkey.include.AhkFolderVariables;
import de.kumpewo.autohotkey.settings.AhkSettings;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.annotations.TestOnly;

import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Where libraries live, in two modes:
 * <ul>
 *   <li>Lib-direct (the setting "Library projects folder" is empty): the files of the user library folder
 *       {@code %A_MyDocuments%\AutoHotkey\Lib} ({@link #userLibrary}) are edited there.</li>
 *   <li>Lib-src (the setting is set, {@link #isLibrarySourceMode}): every library is a project in that folder and is
 *       only deployed into the user library folder. The names match: {@code #Include <v2\Gui>} loads
 *       {@code <Lib>\v2\Gui.ahk}, whose project is {@code <library projects folder>\Gui\} with the source
 *       {@code Gui.ahk} ({@link #projectFolderFor}). New projects lie directly in that folder; the subfolders of the
 *       name only become the deploy target, so one project can deploy several versions ({@code v1\}, {@code v2\}).
 *       The user may still organize projects in subfolders.</li>
 * </ul>
 * A library name ({@link #parseName}) is a path of folder names below the library folder, separated by {@code \} or
 * {@code /}, with the file name last ({@code .ahk} may be given); the spelling is kept.
 */
public final class AhkLibraryLayout {

    /** Characters Windows does not allow in file names. */
    private static final String INVALID = "<>:\"|?*";

    private AhkLibraryLayout() {
    }

    /** Replaces the user library folder in tests ({@code null} = the real one). */
    private static volatile @Nullable Path testLibrary;

    /** Sets the user library folder for a test; {@code null} restores the real one. */
    @TestOnly
    static void setUserLibraryForTests(@Nullable Path library) {
        testLibrary = library;
    }

    /** The user library folder {@code %A_MyDocuments%\AutoHotkey\Lib}, {@code null} if "Documents" is unknown. */
    public static @Nullable Path userLibrary() {
        Path test = testLibrary;
        if (test != null) return test;
        Path documents = AhkFolderVariables.myDocuments();
        return documents == null ? null : documents.resolve("AutoHotkey").resolve("Lib");
    }

    /** The folder of the library projects (setting), {@code null} if it is not set or no valid absolute path. */
    public static @Nullable Path projectsFolder() {
        String folder = AhkSettings.getInstance().getLibraryProjectsFolder();
        if (folder.isEmpty()) return null;
        try {
            Path path = Path.of(folder);
            return path.isAbsolute() ? path.normalize() : null;
        } catch (InvalidPathException e) {
            return null;
        }
    }

    /** Lib-src mode: is the folder of the library projects set? */
    public static boolean isLibrarySourceMode() {
        return projectsFolder() != null;
    }

    /**
     * The segments of a library name ({@code v2\Gui}, {@code v2/Gui.ahk}, {@code Json}), without {@code .ahk};
     * {@code null} if it is empty or a segment is no valid file name ({@code ..}, {@code :}, {@code ?} ...).
     */
    public static @Nullable List<String> parseName(@NotNull String name) {
        String text = name.strip();
        if (text.toLowerCase(Locale.ROOT).endsWith(".ahk")) text = text.substring(0, text.length() - 4);
        if (text.isEmpty()) return null;
        List<String> segments = new ArrayList<>();
        for (String segment : text.split("[\\\\/]", -1)) {
            if (segment.isEmpty() || segment.equals(".") || segment.equals("..") || segment.endsWith(".")
                    || segment.endsWith(" ") || segment.startsWith(" ")) return null;
            for (char c : segment.toCharArray()) {
                if (c < 32 || INVALID.indexOf(c) >= 0) return null;
            }
            segments.add(segment);
        }
        return segments;
    }

    /** The library name of the segments as written in {@code #Include <...>}: {@code v2\Gui}. */
    public static @NotNull String name(@NotNull List<String> segments) {
        return String.join("\\", segments);
    }

    /** The file of the library in the library folder: {@code <lib>\v2\Gui.ahk}. */
    public static @NotNull Path libraryFile(@NotNull Path library, @NotNull List<String> segments) {
        Path file = library;
        for (int i = 0; i < segments.size() - 1; i++) file = file.resolve(segments.get(i));
        return file.resolve(segments.get(segments.size() - 1) + ".ahk");
    }

    /** The project folder of the library, directly in the folder of the projects: {@code v2\Gui} → {@code <projects>\Gui}. */
    public static @NotNull Path projectFolder(@NotNull Path projects, @NotNull List<String> segments) {
        return projects.resolve(segments.get(segments.size() - 1));
    }

    /**
     * The segments of the library name of a file in the library folder ({@code <lib>\v2\Gui.ahk} → {@code v2},
     * {@code Gui}), {@code null} for other files.
     */
    public static @Nullable List<String> nameOf(@NotNull Path file, @Nullable Path library) {
        if (library == null) return null;
        Path normalized = file.toAbsolutePath().normalize();
        Path lib = library.toAbsolutePath().normalize();
        if (!normalized.toString().toLowerCase(Locale.ROOT).startsWith(lib.toString().toLowerCase(Locale.ROOT)
                + java.io.File.separator)) return null;
        Path relative = Path.of(normalized.toString().substring(lib.toString().length() + 1));
        List<String> segments = new ArrayList<>();
        for (Path part : relative) segments.add(part.toString());
        String last = segments.get(segments.size() - 1);
        int dot = last.lastIndexOf('.');
        if (dot > 0) segments.set(segments.size() - 1, last.substring(0, dot));
        return segments;
    }

    /**
     * The project folder that the naming rule gives for a file of the library folder, {@code null} for other files:
     * {@code <lib>\v2\Gui.ahk} → {@code <projects>\Gui}.
     */
    public static @Nullable Path projectFolderFor(@NotNull Path file, @Nullable Path library, @Nullable Path projects) {
        List<String> segments = nameOf(file, library);
        return segments == null || projects == null ? null : projectFolder(projects, segments);
    }
}
