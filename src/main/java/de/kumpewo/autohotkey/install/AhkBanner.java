package de.kumpewo.autohotkey.install;

import de.kumpewo.autohotkey.AhkBundle;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.function.Function;

/**
 * The banner that "Deploy to Local Target..." writes at the start of an installed copy ({@code Banner} in
 * {@code [Deploy]}, for the files that {@code [AddBanner]} includes or that are AutoHotkey files):
 * <ul>
 *   <li>Text ({@link #text}): the value itself ({@code `n} = new line), a built-in template ({@code @library},
 *       {@code @standard}), {@code file:<path>} (relative to the project root, variables like in targets) or
 *       {@code find:<name>} (a plain file name, searched in the project root and then in each folder above it, the
 *       nearest wins; {@link #find}). A file is read as UTF-8, empty lines at the end left out. Placeholders: see
 *       {@link Values}. A line with placeholders that are all empty is
 *       left out; a line without placeholders always stays.</li>
 *   <li>Templates: {@link #LIBRARY} and {@link #STANDARD}: separator lines of 75 dashes like the head of Ahk2Exe
 *       directives, a first line of the same length, an empty line, the same lines from {@code Project} to
 *       {@code Deployed} (values aligned four spaces after the longest label, {@link #LABEL_WIDTH}), in the library
 *       template also {@code Use}.</li>
 *   <li>Comment ({@link #lines}): with a line style every line is {@code <start> text [<end>]}; with a block style the
 *       start comes before the first line, the end after the last one (a text starting or ending with {@code `n} puts
 *       them on lines of their own).</li>
 *   <li>Placement ({@link #apply}): at the start of the file, after a byte order mark and after a first line
 *       {@code <?xml ...}, {@code #!...}, the header of a registry file or {@code @echo off}. A file with a byte order mark of UTF-8 or UTF-16 gets the text in that
 *       encoding; any other file gets ASCII (every character outside 32 to 126 becomes {@code _}). Line ending as at
 *       the end of the first line; CR LF if the file has no line break. The rest is copied byte for byte.</li>
 *   <li>A file that is no AutoHotkey file and whose banner uses {@code {version}} loses its line {@code @SetVersion}
 *       in the copy (the whole line): the version is in the banner already.</li>
 * </ul>
 */
public final class AhkBanner {

    /** The separator line of the templates: with {@code "; "} as long as the lines of the Ahk2Exe head. */
    static final String DASHES = "-".repeat(75);
    /**
     * Width of the label of a template line: the longest label ({@code Deployed}) and four spaces; with
     * {@code "; "} the values start in column 15.
     */
    private static final int LABEL_WIDTH = 12;
    /** The names of all placeholders. */
    private static final List<String> PLACEHOLDERS = List.of("{project-name}", "{project-path}", "{source-name}",
            "{source-relative}", "{source-path}", "{version}", "{commit}", "{timestamp}", "{computer}", "{include}");

    /**
     * The first line of {@link #STANDARD}; as long as the separator line, like {@link #LIBRARY_HEAD}.
     */
    private static final String STANDARD_HEAD =
            "INSTALLED ARTIFACT -- Do not modify here. Edit the source and deploy again.";
    /** The first line of {@link #LIBRARY}; as long as the separator line, like {@link #STANDARD_HEAD}. */
    private static final String LIBRARY_HEAD =
            "INSTALLED LIBRARY  -  Do not modify here. Edit the source and deploy again.";
    /** The part of a template line that names the project folder, left out by {@link #builtin}. */
    private static final String PATH_PART = " [{project-path}]";
    /** The part of a template line that names the computer, left out by {@link #builtin}. */
    private static final String COMPUTER_PART = " on {computer}";
    /** The lines of both templates from {@code Project} to {@code Deployed}. */
    private static final List<String> MIDDLE = List.of(
            label("Project") + "{project-name}" + PATH_PART,
            label("Source") + "{source-relative}",
            label("Version") + "{version}",
            label("Commit") + "{commit}",
            label("Deployed") + "{timestamp}" + COMPUTER_PART);

    /** The template {@code @library}: for the copy of a library in a Lib folder. */
    static final List<String> LIBRARY = template(LIBRARY_HEAD, label("Use") + "{include}");
    /** The template {@code @standard}: for any other installed copy (a tool, a configuration ...). */
    static final List<String> STANDARD = template(STANDARD_HEAD, null);

    /**
     * The values of the placeholders for one copy; {@code null} = empty.
     *
     * @param projectName    {@code {project-name}}: folder name of the project
     * @param projectPath    {@code {project-path}}: absolute path of the project root
     * @param sourceRelative {@code {source-relative}}: source relative to the project root; its file name is
     *                       {@code {source-name}}
     * @param sourcePath     {@code {source-path}}: absolute path of the source
     * @param version        {@code {version}}: value of the version line of the source
     * @param commit         {@code {commit}}: short hash of the Git HEAD of the project, with {@code +} if the source
     *                       has uncommitted changes; empty without Git or for a file that is not versioned
     * @param timestamp      {@code {timestamp}}: local time of the deployment, {@code yyyy-MM-dd HH:mm}
     * @param include        {@code {include}}: the line {@code #Include <name>} for a target in a Lib folder
     * @param computer       {@code {computer}}: name of the computer of the deployment
     */
    record Values(@NotNull String projectName, @NotNull String projectPath, @NotNull String sourceRelative,
                  @Nullable String sourcePath, @Nullable String version, @Nullable String commit,
                  @NotNull String timestamp, @Nullable String include, @Nullable String computer) {

        /** The value of the placeholder (with braces), empty for {@code null}. */
        @NotNull String of(@NotNull String placeholder) {
            String value = switch (placeholder) {
                case "{project-name}" -> projectName;
                case "{project-path}" -> projectPath;
                case "{source-name}" -> sourceRelative.substring(
                        Math.max(sourceRelative.lastIndexOf('\\'), sourceRelative.lastIndexOf('/')) + 1);
                case "{source-relative}" -> sourceRelative;
                case "{source-path}" -> sourcePath;
                case "{version}" -> version;
                case "{commit}" -> commit;
                case "{timestamp}" -> timestamp;
                case "{include}" -> include;
                case "{computer}" -> computer;
                default -> null;
            };
            return value == null ? "" : value;
        }
    }

    private AhkBanner() {
    }

    /** The label of a template line, padded to the column of the values. */
    private static String label(String name) {
        return name + " ".repeat(LABEL_WIDTH - name.length());
    }

    /**
     * A template: separator, the first line, an empty line, {@link #MIDDLE}, the extra line (if any), separator.
     */
    private static List<String> template(@NotNull String head, @Nullable String extra) {
        List<String> lines = new ArrayList<>(List.of(DASHES, head, ""));
        lines.addAll(MIDDLE);
        if (extra != null) lines.add(extra);
        lines.add(DASHES);
        return List.copyOf(lines);
    }

    /**
     * The lines of the text of {@code Banner} (placeholders not yet replaced); {@code null} after a mistake, which
     * is added to the errors.
     *
     * @param banner    {@code Banner} as written
     * @param root      the project root, for {@code file:} and {@code find:}
     * @param variables the variables of the targets, for the path of {@code file:}
     */
    static @Nullable List<String> text(@NotNull String banner, @NotNull Path root,
                                       @NotNull Function<String, String> variables, @NotNull List<String> errors) {
        String value = banner.strip();
        String lower = value.toLowerCase(Locale.ROOT);
        if (value.startsWith("@")) {
            if (!lower.equals("@library") && !lower.equals("@standard")) {
                errors.add(AhkBundle.message("deploy.banner.template.unknown", value));
                return null;
            }
            return lower.equals("@library") ? LIBRARY : STANDARD;
        }
        if (lower.startsWith("find:")) {
            String name = value.substring(5).strip();
            if (!isPlainName(name)) {
                errors.add(AhkBundle.message("deploy.banner.find.name", value));
                return null;
            }
            Path found = find(root, name);
            if (found == null) {
                errors.add(AhkBundle.message("deploy.banner.find.missing", name, root));
                return null;
            }
            return read(found, errors);
        }
        if (!lower.startsWith("file:")) return Arrays.asList(value.split("`n", -1));
        String written = value.substring(5).strip();
        String expanded = AhkDeployPlan.expand(written, variables, null, errors, "Banner");
        if (expanded == null) return null;
        Path file;
        try {
            file = root.resolve(expanded).normalize();
        } catch (InvalidPathException e) {
            errors.add(AhkBundle.message("deploy.banner.file.missing", written));
            return null;
        }
        if (!Files.isRegularFile(file)) {
            errors.add(AhkBundle.message("deploy.banner.file.missing", file));
            return null;
        }
        return read(file, errors);
    }

    /**
     * The text without parts of the built-in templates that must not or cannot be told; any other text unchanged
     * (there the text itself decides).
     *
     * @param text     lines of the text ({@link #text})
     * @param path     false: without the project folder
     * @param computer false: without the name of the computer
     */
    static @NotNull List<String> builtin(@NotNull List<String> text, boolean path, boolean computer) {
        if (text != LIBRARY && text != STANDARD) return text;
        List<String> result = new ArrayList<>();
        for (String line : text) {
            if (!path) line = line.replace(PATH_PART, "");
            if (!computer) line = line.replace(COMPUTER_PART, "");
            result.add(line);
        }
        return result;
    }

    /**
     * The file {@code name} in {@code folder} or in the nearest folder above it ({@code find:}); {@code null} if no
     * folder up to the root of the drive contains it.
     */
    static @Nullable Path find(@NotNull Path folder, @NotNull String name) {
        for (Path dir = folder.toAbsolutePath().normalize(); dir != null; dir = dir.getParent()) {
            Path file = dir.resolve(name);
            if (Files.isRegularFile(file)) return file;
        }
        return null;
    }

    /** Whether {@code name} is a plain file name for {@code find:}: not empty, no folder, no variable. */
    private static boolean isPlainName(@NotNull String name) {
        return !name.isEmpty() && !name.equals(".") && !name.equals("..")
                && name.chars().noneMatch(c -> c == '\\' || c == '/' || c == ':' || c == '%');
    }

    /**
     * The lines of a banner file (UTF-8, byte order mark and empty lines at the end left out); {@code null} after a
     * mistake, which is added to the errors.
     */
    private static @Nullable List<String> read(@NotNull Path file, @NotNull List<String> errors) {
        try {
            String text = AhkDeployer.readText(file);
            if (text.startsWith("﻿")) text = text.substring(1);
            List<String> lines = new ArrayList<>(Arrays.asList(text.split("\\r\\n|\\n|\\r", -1)));
            while (!lines.isEmpty() && lines.get(lines.size() - 1).isBlank()) lines.remove(lines.size() - 1);
            return lines;
        } catch (IOException e) {
            errors.add(AhkBundle.message("deploy.banner.file.error", file, e.getMessage()));
            return null;
        }
    }

    /**
     * The comment lines (without line endings) of the banner for one file; a line whose placeholders are all empty is
     * left out. Empty if no line is left.
     *
     * @param text   lines of the text ({@link #text})
     * @param style  the comment style
     * @param values the values of the placeholders
     */
    static @NotNull List<String> lines(@NotNull List<String> text, @NotNull AhkFileRules.Style style,
                                      @NotNull Values values) {
        List<String> replaced = new ArrayList<>();
        for (String line : text) {
            boolean placeholder = false;
            boolean filled = false;
            String result = line;
            for (String name : PLACEHOLDERS) {
                if (!line.contains(name)) continue;
                String value = values.of(name);
                placeholder = true;
                if (!value.isEmpty()) filled = true;
                result = result.replace(name, value);
            }
            if (!placeholder || filled) replaced.add(result);
        }
        List<String> result = new ArrayList<>();
        if (!style.block()) {
            for (String line : replaced) {
                result.add(join(style.start(), line, style.end()));
            }
            return result;
        }
        int last = replaced.size() - 1;
        for (int i = 0; i <= last; i++) {
            String line = replaced.get(i);
            String start = i == 0 ? style.start() : null;
            String end = i == last ? style.end() : null;
            result.add(join(start, line, end));
        }
        return result;
    }

    /** {@code start text end}, each separated by one space, empty parts left out. */
    private static String join(@Nullable String start, String text, @Nullable String end) {
        StringBuilder result = new StringBuilder();
        for (String part : new String[]{start, text, end}) {
            if (part == null || part.isEmpty()) continue;
            if (!result.isEmpty()) result.append(' ');
            result.append(part);
        }
        return result.toString().stripTrailing();
    }

    /** Does the text of the banner use the placeholder (with braces, e.g. {@code {version}})? */
    static boolean uses(@NotNull List<String> text, @NotNull String placeholder) {
        for (String line : text) if (line.contains(placeholder)) return true;
        return false;
    }

    /** The content of the copy: the banner lines in front of the content of the source (see class comment). */
    public static byte @NotNull [] apply(byte @NotNull [] source, @NotNull List<String> lines) {
        return apply(source, lines, -1);
    }

    /**
     * As {@link #apply(byte[], List)}, and the line {@code dropLine} (0-based, -1 = none) of the source is left out
     * with its line break.
     */
    public static byte @NotNull [] apply(byte @NotNull [] source, @NotNull List<String> lines, int dropLine) {
        int bom = 0;
        Charset charset = null;
        if (source.length >= 3 && (source[0] & 0xFF) == 0xEF && (source[1] & 0xFF) == 0xBB && (source[2] & 0xFF) == 0xBF) {
            bom = 3;
            charset = StandardCharsets.UTF_8;
        } else if (source.length >= 2 && (source[0] & 0xFF) == 0xFF && (source[1] & 0xFF) == 0xFE) {
            bom = 2;
            charset = StandardCharsets.UTF_16LE;
        } else if (source.length >= 2 && (source[0] & 0xFF) == 0xFE && (source[1] & 0xFF) == 0xFF) {
            bom = 2;
            charset = StandardCharsets.UTF_16BE;
        }
        Charset decode = charset == null ? StandardCharsets.ISO_8859_1 : charset;
        String rest = new String(source, bom, source.length - bom, decode);
        String eol = lineEnding(rest);
        // a first line <?xml ...?> or #!... stays first
        int header = 0;
        boolean headerWithoutBreak = false;
        if (hasHeaderLine(rest)) {
            int lf = indexOfLineEnd(rest);
            if (lf < 0) {
                header = rest.length();
                headerWithoutBreak = true;
            } else {
                header = lf;
            }
        }
        StringBuilder text = new StringBuilder();
        if (headerWithoutBreak) text.append(eol);
        for (String line : lines) text.append(charset == null ? ascii(line) : line).append(eol);
        int headerBytes = header == 0 ? 0 : rest.substring(0, header).getBytes(decode).length;
        int[] drop = lineRange(rest, dropLine);
        if (drop != null && drop[0] < header) drop = null;
        ByteArrayOutputStream out = new ByteArrayOutputStream(source.length + text.length() * 2);
        out.write(source, 0, bom + headerBytes);
        out.writeBytes(text.toString().getBytes(charset == null ? StandardCharsets.US_ASCII : charset));
        if (drop == null) {
            out.write(source, bom + headerBytes, source.length - bom - headerBytes);
        } else {
            int dropStart = bom + rest.substring(0, drop[0]).getBytes(decode).length;
            int dropEnd = bom + rest.substring(0, drop[1]).getBytes(decode).length;
            out.write(source, bom + headerBytes, dropStart - bom - headerBytes);
            out.write(source, dropEnd, source.length - dropEnd);
        }
        return out.toByteArray();
    }

    /** Start and end (after its line break) of the 0-based line, counted by LF; {@code null} if there is none. */
    private static int @Nullable [] lineRange(String text, int line) {
        if (line < 0) return null;
        int start = 0;
        for (int i = 0; i < line; i++) {
            int lf = text.indexOf('\n', start);
            if (lf < 0) return null;
            start = lf + 1;
        }
        if (start >= text.length()) return null;
        int lf = text.indexOf('\n', start);
        return new int[]{start, lf < 0 ? text.length() : lf + 1};
    }

    /**
     * Does the text start with a line that must stay first ({@code <?xml ...}, {@code #!...}, the header of a
     * registry file, {@code Windows Registry Editor Version 5.00} or {@code REGEDIT4}, or {@code @echo off} of a
     * batch file, also with more commands behind it)? Case is ignored.
     */
    static boolean hasHeaderLine(@NotNull CharSequence text) {
        String start = text.subSequence(0, Math.min(25, text.length())).toString().toLowerCase(java.util.Locale.ROOT);
        return start.startsWith("#!") || start.startsWith("<?xml") || start.startsWith("windows registry editor")
                || start.startsWith("regedit4") || start.startsWith("@echo off");
    }

    /** Index after the line ending of the first line, -1 if there is none. */
    static int indexOfLineEnd(@NotNull CharSequence text) {
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c == '\n') return i + 1;
            if (c == '\r') return i + 1 < text.length() && text.charAt(i + 1) == '\n' ? i + 2 : i + 1;
        }
        return -1;
    }

    /** The line ending at the end of the first line: CR LF, LF or CR; CR LF if the text has no line break. */
    private static String lineEnding(String text) {
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c == '\n') return "\n";
            if (c == '\r') return i + 1 < text.length() && text.charAt(i + 1) == '\n' ? "\r\n" : "\r";
        }
        return "\r\n";
    }

    /** The text in ASCII: every character outside 32 to 126 becomes {@code _}. */
    static @NotNull String ascii(@NotNull String text) {
        StringBuilder result = new StringBuilder(text.length());
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (Character.isHighSurrogate(c) && i + 1 < text.length() && Character.isLowSurrogate(text.charAt(i + 1))) {
                i++;   // one character outside the BMP: one "_"
                result.append('_');
            } else {
                result.append(c >= 32 && c <= 126 ? c : '_');
            }
        }
        return result.toString();
    }
}
