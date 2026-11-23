package de.kumpewo.autohotkey.install;

import de.kumpewo.autohotkey.AhkBundle;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/**
 * Creates a library project ({@link AhkLibraryLayout}): the folder {@code <projects>\Gui} (for {@code v2\Gui}), the source
 * {@code Gui.ahk} (new: {@code #Requires AutoHotkey v2.0} and {@code ;@Ahk2Exe-SetVersion 0.1.0}; or the bytes of an
 * existing library file) and the {@code settings.ahk-v2-plugin.ini} from {@value #TEMPLATE} ({@code $name$} = library
 * name, {@code $project$} = name of the project folder, {@code $target$} = the target of the source, i.e. the
 * subfolders of the library name with a final {@code \} or {@code .}, {@code $file$} = the source; not in braces,
 * which the placeholders of {@code Banner} use; CR LF): {@code Destination} is always the user library folder
 * itself, so that a project of {@code v2\Gui} deploys {@code Gui.ahk=v2\} and can get further targets in other
 * library folders ({@code Gui-v1.ahk=Gui.ahk}) without changing {@code Destination};
 * {@code Banner} is {@code find:}{@value #SHARED_BANNER} if the projects folder or a folder above it contains that
 * file ({@link AhkBanner#find}), otherwise {@code @library}, and {@code [SetVersion]} includes all AutoHotkey files. An existing
 * folder is never touched. No Git repository is created.
 */
public final class AhkNewLibraryProject {

    /** The template of the settings file of a library project. */
    static final String TEMPLATE = "/install/settings-library.ahk-v2-plugin.ini";

    /** Content of a new library source. */
    static final String NEW_SOURCE = "#Requires AutoHotkey v2.0\r\n;@Ahk2Exe-SetVersion 0.1.0\r\n\r\n";

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
    static @NotNull String settings(@NotNull List<String> segments, boolean sharedBanner) throws IOException {
        String text;
        try (InputStream in = AhkNewLibraryProject.class.getResourceAsStream(TEMPLATE)) {
            if (in == null) throw new IOException(TEMPLATE);
            text = new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
        StringBuilder target = new StringBuilder();
        for (int i = 0; i < segments.size() - 1; i++) target.append(segments.get(i)).append('\\');
        if (target.isEmpty()) target.append('.');
        return text.replace("\r\n", "\n").replace("$name$", AhkLibraryLayout.name(segments))
                .replace("$project$", segments.get(segments.size() - 1))
                .replace("$target$", target).replace("$file$", segments.get(segments.size() - 1) + ".ahk")
                .replace("$library-banner$", sharedBanner ? ";" : "").replace("$file-banner$", sharedBanner ? "" : ";")
                .replace("\n", "\r\n");
    }
}
