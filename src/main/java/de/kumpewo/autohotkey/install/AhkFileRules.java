package de.kumpewo.autohotkey.install;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * The lines {@code selector=value} of {@code [SetVersion]} and {@code [AddBanner]}: the selector is an
 * {@link AhkPathPattern}, the value says whether the files are included and how a comment is written in them:
 * <ul>
 *   <li>{@code yes}: included, comment of the file type ({@link #builtinStyle});</li>
 *   <li>{@code no}: excluded;</li>
 *   <li>{@code line:<start> [<end>]}: included, every comment line is {@code <start> text [<end>]};</li>
 *   <li>{@code block:<start> <end>}: included, one block comment {@code <start> text ... <end>}.</li>
 * </ul>
 * Keywords in any case. The last matching line wins ({@link #find}), as in {@code .gitignore}. Binary files
 * ({@link #isBinary}) are never changed, whatever a line says.
 */
public final class AhkFileRules {

    /**
     * How a comment is written: {@code block == false}: every line {@code start text [end]}; {@code block == true}: one
     * comment from {@code start} to {@code end}.
     */
    public record Style(@NotNull String start, @Nullable String end, boolean block) {
    }

    /**
     * The built-in style of {@code .cmd} and {@code .bat}: {@code ::}. When a version line is read in this style,
     * {@code REM} is recognized too ({@link #readStarts}); an own style {@code line:REM} writes {@code REM}.
     */
    static final Style BATCH = new Style("::", null, false);

    /**
     * The comment starts that a version line in this style may have: the start of the style, for the built-in
     * {@link #BATCH} style also {@code REM} (older batch files).
     */
    public static @NotNull List<String> readStarts(@NotNull Style style) {
        return style.equals(BATCH) ? List.of(style.start(), "REM") : List.of(style.start());
    }

    /**
     * One line of the section.
     *
     * @param pattern the selector
     * @param include included (otherwise excluded)
     * @param style   the comment style given in the value, {@code null} = that of the file type
     * @param value   the value as written (for the overview)
     * @param line    1-based line in the file
     */
    public record Rule(@NotNull AhkPathPattern pattern, boolean include, @Nullable Style style, @NotNull String value,
                       int line) {
    }

    /** AutoHotkey file types (lower-case extensions): implicitly included. */
    static final List<String> AUTOHOTKEY_TYPES = List.of("ahk", "ah2", "ahk2");

    /**
     * Built-in comment styles by lower-case extension. Not included: {@code .txt} (no comment), {@code .json} (no
     * comment), {@code .php} (a comment before {@code <?php} would be output), {@code .m} (MATLAB or Objective-C).
     */
    private static final Map<String, Style> BUILTIN = builtin();

    /** Fills {@link #BUILTIN}: one comment style for each group of extensions. */
    private static Map<String, Style> builtin() {
        Map<String, Style> map = new java.util.HashMap<>();
        // the field BATCH is declared before BUILTIN and is set by now
        put(map, new Style(";", null, false), "ahk", "ah2", "ahk2", "ini", "au3", "asm", "reg");
        put(map, new Style("#", null, false), "ps1", "psm1", "psd1", "py", "pyw", "sh", "bash", "rb", "pl", "r",
                "yaml", "yml", "toml", "conf", "cfg", "properties");
        put(map, new Style("//", null, false), "js", "mjs", "ts", "java", "kt", "kts", "groovy", "gradle", "cs", "c",
                "cpp", "cc", "h", "hpp", "go", "rs", "swift", "scala", "dart");
        put(map, new Style("/*", "*/", true), "css", "scss", "less");
        put(map, new Style("--", null, false), "sql", "lua", "hs");
        put(map, BATCH, "cmd", "bat");
        put(map, new Style("'", null, false), "vbs", "bas");
        put(map, new Style("<!--", "-->", true), "xml", "xaml", "htm", "html", "svg", "md");
        put(map, new Style("%", null, false), "tex");
        return Map.copyOf(map);
    }

    /** Puts the style for each of the extensions into the map. */
    private static void put(@NotNull Map<String, Style> map, @NotNull Style style, String @NotNull ... extensions) {
        for (String extension : extensions) map.put(extension, style);
    }

    /** Binary file types (lower-case extensions): never changed. */
    private static final List<String> BINARY = List.of("exe", "dll", "png", "jpg", "jpeg", "gif", "bmp", "ico", "zip",
            "pdf", "chm");

    private AhkFileRules() {
    }

    /** The lower-case extension of the file name, {@code ""} without one. */
    static @NotNull String extension(@NotNull String fileName) {
        int slash = Math.max(fileName.lastIndexOf('/'), fileName.lastIndexOf('\\'));
        int dot = fileName.lastIndexOf('.');
        return dot > slash ? fileName.substring(dot + 1).toLowerCase(Locale.ROOT) : "";
    }

    /** Is the file an AutoHotkey file ({@code .ahk}, {@code .ah2}, {@code .ahk2})? */
    public static boolean isAutoHotkey(@NotNull String fileName) {
        return AUTOHOTKEY_TYPES.contains(extension(fileName));
    }

    /** Is the file of a binary type that is never changed? */
    public static boolean isBinary(@NotNull String fileName) {
        return BINARY.contains(extension(fileName));
    }

    /** The comment style of the file type, {@code null} if the plugin does not know one. */
    public static @Nullable Style builtinStyle(@NotNull String fileName) {
        return BUILTIN.get(extension(fileName));
    }

    /** The style of the rule, otherwise that of the file type; {@code null} if neither is known. */
    public static @Nullable Style style(@Nullable Rule rule, @NotNull String fileName) {
        return rule != null && rule.style() != null ? rule.style() : builtinStyle(fileName);
    }

    /**
     * Reads a value; {@code null} if it is none of the forms above. {@code include[0]} is set to yes/no.
     */
    static @Nullable Style parseValue(@NotNull String value, boolean @NotNull [] include) {
        String text = value.strip();
        String lower = text.toLowerCase(Locale.ROOT);
        switch (lower) {
            case "yes" -> {
                include[0] = true;
                return null;
            }
            case "no" -> {
                include[0] = false;
                return null;
            }
            default -> {
            }
        }
        include[0] = true;
        if (lower.startsWith("line:")) {
            String[] parts = text.substring(5).strip().split("\\s+");
            if (parts.length < 1 || parts.length > 2 || parts[0].isEmpty()) return INVALID;
            return new Style(parts[0], parts.length == 2 ? parts[1] : null, false);
        }
        if (lower.startsWith("block:")) {
            String[] parts = text.substring(6).strip().split("\\s+");
            if (parts.length != 2 || parts[0].isEmpty()) return INVALID;
            return new Style(parts[0], parts[1], true);
        }
        return INVALID;
    }

    /** Marker of an invalid value in {@link #parseValue} (never part of a rule). */
    static final Style INVALID = new Style("", null, false);

    /** The last rule whose selector names the file (relative to the project root), {@code null} = none. */
    public static @Nullable Rule find(@NotNull List<Rule> rules, @NotNull String relative) {
        Rule result = null;
        for (Rule rule : rules) {
            if (rule.pattern().matches(relative)) result = rule;
        }
        return result;
    }
}
