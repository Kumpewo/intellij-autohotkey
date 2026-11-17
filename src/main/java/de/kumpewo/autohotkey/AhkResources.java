package de.kumpewo.autohotkey;

import de.kumpewo.autohotkey.parser.AhkLanguageLevel;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.function.Function;

/**
 * Data sets of the plugin per language level ({@link AhkLanguageLevel}). The files of v2.0 lie directly in their
 * folder ({@code /names/functions.txt}); a level with its own {@link AhkLanguageLevel#folder folder} may have a file
 * of the same name in the subfolder ({@code /names/v2.1/functions.txt}). Depending on the kind of data set that file
 * is
 * <ul>
 *   <li>a <b>difference</b> to the v2.0 file ({@link #words}, {@link #lines}): only what is new or different, an
 *       entry {@code -Key} removes the v2.0 entries with that key; without the file the level uses the v2.0 data,</li>
 *   <li>or a <b>complete copy</b> ({@link #complete}, for files generated from the help).</li>
 * </ul>
 * Comments start with {@code ;} and run to the end of the line.
 */
public final class AhkResources {

    private AhkResources() {
    }

    /** Text of a resource (UTF-8) or {@code null} if it does not exist. */
    public static @Nullable String read(@NotNull String path) {
        try (InputStream in = AhkResources.class.getResourceAsStream(path)) {
            if (in == null) return null;
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new IllegalStateException("Resource not readable: " + path, e);
        }
    }

    /**
     * Path of the file of the level: {@code folder/level/name} if the level has a folder and the file exists there,
     * otherwise {@code folder/name} ({@code folder} e.g. {@code /names}, {@code name} e.g. {@code members.txt}).
     */
    public static @NotNull String complete(@NotNull AhkLanguageLevel level, @NotNull String folder,
                                           @NotNull String name) {
        String own = levelPath(level, folder, name);
        return own != null && AhkResources.class.getResource(own) != null ? own : folder + "/" + name;
    }

    /**
     * Words of a name list (one name per line or separated by spaces, commas or quotes): those of the v2.0 file,
     * then the difference of the level: a word is added, {@code -Word} removes it (case-insensitive). The v2.0 file
     * must exist.
     */
    public static @NotNull List<String> words(@NotNull AhkLanguageLevel level, @NotNull String folder,
                                              @NotNull String name) {
        List<String> result = new ArrayList<>(splitWords(required(folder + "/" + name)));
        String own = levelPath(level, folder, name);
        String difference = own == null ? null : read(own);
        if (difference == null) return result;
        for (String word : splitWords(difference)) {
            if (word.startsWith("-") && word.length() > 1) {
                String removed = word.substring(1);
                result.removeIf(w -> w.equalsIgnoreCase(removed));
            } else {
                result.add(word);
            }
        }
        return result;
    }

    /**
     * Lines without comment and empty lines: those of the v2.0 file, merged with the difference of the level. Each
     * line of the difference replaces all v2.0 lines with the same key (the new lines come at the end, in their
     * order); {@code -Key} removes them. The key of a line is given by {@code key} (compared in lower case), e.g. the
     * name of a signature. The v2.0 file must exist.
     */
    public static @NotNull List<String> lines(@NotNull AhkLanguageLevel level, @NotNull String folder,
                                              @NotNull String name, @NotNull Function<String, String> key) {
        List<String> base = splitLines(required(folder + "/" + name));
        String own = levelPath(level, folder, name);
        String difference = own == null ? null : read(own);
        return difference == null ? base : merge(base, splitLines(difference), key);
    }

    /**
     * Merges prepared lines (without comments) as described in {@link #lines}: each line of {@code difference}
     * replaces the lines of {@code base} with the same key, {@code -Key} removes them.
     */
    public static @NotNull List<String> merge(@NotNull List<String> base, @NotNull List<String> difference,
                                              @NotNull Function<String, String> key) {
        Set<String> replaced = new HashSet<>();
        List<String> added = new ArrayList<>();
        for (String line : difference) {
            if (line.startsWith("-") && line.length() > 1) {
                replaced.add(lower(key.apply(line.substring(1).trim())));
            } else {
                replaced.add(lower(key.apply(line)));
                added.add(line);
            }
        }
        List<String> result = new ArrayList<>();
        for (String line : base) {
            if (!replaced.contains(lower(key.apply(line)))) result.add(line);
        }
        result.addAll(added);
        return result;
    }

    /** Non-empty lines without comment ({@code ;} to the end of the line), trimmed. */
    public static @NotNull List<String> splitLines(@NotNull String text) {
        List<String> lines = new ArrayList<>();
        for (String line : text.split("\r?\n")) {
            int comment = line.indexOf(';');
            if (comment >= 0) line = line.substring(0, comment);
            line = line.trim();
            if (!line.isEmpty()) lines.add(line);
        }
        return lines;
    }

    /** Words of a name list (comments removed; separated by whitespace, commas or quotes). */
    private static List<String> splitWords(String text) {
        List<String> words = new ArrayList<>();
        for (String line : text.split("\r?\n")) {
            int comment = line.indexOf(';');
            if (comment >= 0) line = line.substring(0, comment);
            for (String word : line.split("[\\s,\"]+")) {
                if (!word.isEmpty()) words.add(word);
            }
        }
        return words;
    }

    /** {@code folder/level/name} or {@code null} if the level has no folder of its own. */
    public static @Nullable String levelPath(AhkLanguageLevel level, String folder, String name) {
        String own = level.dataLevel().folder;
        return own == null ? null : folder + "/" + own + "/" + name;
    }

    /** Text of a resource that must exist; a missing file is a programming error. */
    private static String required(String path) {
        String text = read(path);
        if (text == null) throw new IllegalStateException("Data file missing: " + path);
        return text;
    }

    /** Key in lower case. */
    private static String lower(String key) {
        return key.toLowerCase(Locale.ROOT);
    }
}
