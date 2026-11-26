package de.kumpewo.autohotkey.install;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The names of the sources of a library that exists for several versions of AutoHotkey: {@code Encode-v2.ahk} in the
 * project is deployed as {@code v2\Encode.ahk}. A version folder is {@code v} and numbers with dots ({@code v1},
 * {@code v2.1}).
 */
final class AhkVersionedName {

    /** A version folder. */
    private static final Pattern FOLDER = Pattern.compile("[vV]\\d+(?:\\.\\d+)*");

    /** A source with a version: name, version, extension; the last {@code -v} counts. */
    private static final Pattern SOURCE = Pattern.compile("(.+)-([vV]\\d+(?:\\.\\d+)*)(\\.[^.]+)");

    private AhkVersionedName() {
    }

    /** Is the folder name a version ({@code v2})? */
    static boolean isVersion(@Nullable String folder) {
        return folder != null && FOLDER.matcher(folder).matches();
    }

    /** The name of the source in the project: {@code Encode.ahk} and {@code v2} give {@code Encode-v2.ahk}. */
    static @NotNull String source(@NotNull String fileName, @NotNull String version) {
        int dot = fileName.lastIndexOf('.');
        return dot > 0 ? fileName.substring(0, dot) + "-" + version + fileName.substring(dot)
                : fileName + "-" + version;
    }

    /**
     * The target of a source as written in the settings file: {@code Encode-v2.ahk} gives {@code v2\Encode.ahk}, only
     * the file name counts; {@code null} for other names, files that are no AutoHotkey files, and wildcards.
     */
    static @Nullable String target(@NotNull String source) {
        String name = source.substring(Math.max(source.lastIndexOf('\\'), source.lastIndexOf('/')) + 1);
        if (source.indexOf('*') >= 0 || source.indexOf('?') >= 0 || !AhkFileRules.isAutoHotkey(name)) return null;
        Matcher matcher = SOURCE.matcher(name);
        return matcher.matches() ? matcher.group(2) + "\\" + matcher.group(1) + matcher.group(3) : null;
    }

    /** The {@code #Requires} version of a new source for the version folder ({@code null}: none). */
    static @NotNull String requirement(@Nullable String version) {
        if (version == null || !isVersion(version)) return "v2.0";
        String number = version.substring(1);
        if (number.equals("1") || number.startsWith("1.")) return "v1.1";
        // a pre-release does not satisfy "v2.1"
        return number.equals("2.1") ? "v2.1-alpha" : "v2.0";
    }
}
