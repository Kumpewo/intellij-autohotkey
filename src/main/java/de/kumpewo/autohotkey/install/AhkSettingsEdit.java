package de.kumpewo.autohotkey.install;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.io.IOException;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.function.Predicate;

/**
 * Changes of the text of a {@code settings.ahk-v2-plugin.ini} that keep everything else as it is (comments, order, line
 * separators, encoding):
 * <ul>
 *   <li>{@link #withLine}: a new line at the end of a section ("Add to Library Project...").</li>
 *   <li>{@link #rename}: the lines that name a renamed or moved file or folder of the project
 *       ({@link AhkSettingsRenameListener}): the sources of {@code [Install]} and {@code [InstallIfMissing]} and the
 *       selectors of {@code [SetVersion]} and {@code [AddBanner]} that name exactly this path (no wildcards),
 *       the copy line of the executable of a renamed script, and, depending on the {@link TargetMode}, the targets
 *       and {@code [Cleanup]}.</li>
 * </ul>
 */
public final class AhkSettingsEdit {

    /** Byte order mark, possibly at the start of the first line. */
    private static final char BOM = '\uFEFF';

    private AhkSettingsEdit() {
    }

    /**
     * What happens to the target of a copy line whose source changes its file name (a folder target or a target with
     * the old file name).
     */
    public enum TargetMode {
        /** Only the source changes; a folder target then takes the new name. */
        SOURCE_ONLY,
        /** The target keeps the old file name: a folder target gets the old name appended. */
        KEEP_NAME,
        /**
         * The target takes the new name: a target with the old file name gets the new one; for {@code [Install]}
         * a line in {@code [Cleanup]} removes the old copy at the next deployment.
         */
        RENAME
    }

    /**
     * One changed line, for the dialog.
     *
     * @param line    1-based line in the old text, 0 for an added line
     * @param oldText the line before the change (trimmed), {@code null} for an added line
     * @param newText the line after the change (trimmed), {@code null} for a removed line
     * @param section the section of the line as written in the file
     */
    public record LineEdit(int line, @Nullable String oldText, @Nullable String newText, @NotNull String section) {
    }

    /**
     * The result of {@link #rename}.
     *
     * @param text       the new text
     * @param edits      the changed lines in file order, added lines at the end
     * @param choice     true if the target of a copy line depends on the {@link TargetMode}
     * @param executable true if the copy line of the executable of the renamed script changed
     * @param caret      offset of the target of the first changed copy line in the new text (start of the first
     *                   changed line without copy line), -1 if lines were added or nothing changed
     */
    public record Rename(@NotNull String text, @NotNull List<LineEdit> edits, boolean choice, boolean executable,
                         int caret) {
    }

    /** A part of a line: offsets in the line and the text. */
    private record Span(int start, int end, @NotNull String text) {
    }

    /** A line of {@code [Cleanup]}: index of the line, files and folder. */
    private record CleanupLine(int index, @NotNull String selector, @NotNull String folder) {
    }

    /** A line of the text with its offsets (without the line separator). */
    private record Line(int start, int end, @NotNull String text) {
    }

    /** The lines of the text; separators {@code \r\n}, {@code \n} and {@code \r}. */
    private static List<Line> lines(@NotNull CharSequence text) {
        List<Line> lines = new ArrayList<>();
        int start = 0;
        int i = 0;
        while (i < text.length()) {
            char c = text.charAt(i);
            if (c == '\n' || c == '\r') {
                lines.add(new Line(start, i, text.subSequence(start, i).toString()));
                if (c == '\r' && i + 1 < text.length() && text.charAt(i + 1) == '\n') i++;
                start = i + 1;
            }
            i++;
        }
        lines.add(new Line(start, text.length(), text.subSequence(start, text.length()).toString()));
        return lines;
    }

    /** The line separator of the text: the first one in it, {@code \r\n} if there is none. */
    static @NotNull String separator(@NotNull CharSequence text) {
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c == '\n') return "\n";
            if (c == '\r') return i + 1 < text.length() && text.charAt(i + 1) == '\n' ? "\r\n" : "\r";
        }
        return "\r\n";
    }

    /** The name of the section if the line is a section header ({@code [Name]}), lower case; otherwise {@code null}. */
    private static @Nullable String section(@NotNull String line) {
        String text = line.strip();
        if (!text.isEmpty() && text.charAt(0) == BOM) text = text.substring(1).strip();
        return text.startsWith("[") && text.endsWith("]")
                ? text.substring(1, text.length() - 1).strip().toLowerCase(Locale.ROOT) : null;
    }

    /** Is the line a key line (not empty, no comment, no section header)? */
    private static boolean isEntry(@NotNull String line) {
        String text = line.strip();
        return !text.isEmpty() && !text.startsWith(";") && section(line) == null;
    }

    /**
     * The text with the line added at the end of the section (after its last key line, before comments and empty
     * lines that follow it); a missing section is added at the end of the text, after an empty line. The section
     * has its current name.
     */
    public static @NotNull String withLine(@NotNull String text, @NotNull String section, @NotNull String line) {
        return withLine(text, section, line, false);
    }

    /**
     * As {@link #withLine(String, String, String)}; {@code legacy}: the text of a {@code settings.ahk-plugin.ini} of
     * version 2.1, which gets the line in the section of the old name ({@link AhkSettingsFormat#written}).
     */
    public static @NotNull String withLine(@NotNull String text, @NotNull String section, @NotNull String line,
                                           boolean legacy) {
        String nl = separator(text);
        List<Line> lines = lines(text);
        section = AhkSettingsFormat.written(section, legacy);
        String wanted = section.toLowerCase(Locale.ROOT);
        for (int i = 0; i < lines.size(); i++) {
            if (!wanted.equals(section(lines.get(i).text()))) continue;
            int after = i;
            for (int j = i + 1; j < lines.size() && section(lines.get(j).text()) == null; j++) {
                if (isEntry(lines.get(j).text())) after = j;
            }
            Line last = lines.get(after);
            if (after == lines.size() - 1) {
                // the last line of the text, possibly without separator
                return text.substring(0, last.end()) + nl + line + nl + text.substring(last.end()).stripLeading();
            }
            int insert = lines.get(after + 1).start();
            return text.substring(0, insert) + line + nl + text.substring(insert);
        }
        StringBuilder result = new StringBuilder(text);
        if (!text.isEmpty() && !text.endsWith("\n") && !text.endsWith("\r")) result.append(nl);
        if (!text.isBlank()) result.append(nl);
        return result.append('[').append(section).append(']').append(nl).append(line).append(nl).toString();
    }

    /** The path written in a key: separators as {@code \}, lower case, without leading and trailing separator. */
    private static @NotNull String normalized(@NotNull String path) {
        String text = path.replace('/', '\\').toLowerCase(Locale.ROOT);
        while (text.startsWith("\\")) text = text.substring(1);
        while (text.endsWith("\\")) text = text.substring(0, text.length() - 1);
        return text;
    }

    /**
     * The new text for a file or folder of the project that was renamed or moved:
     * <ul>
     *   <li>every source of {@code [Install]} and {@code [InstallIfMissing]} that names the old path (or, for a folder,
     *       a path below it) gets the new path; separators, quotes and a leading or trailing {@code /} are kept;</li>
     *   <li>for a renamed {@code .ahk} file also the source of the executable of the same name next to it (the one
     *       compiled from it before the deployment);</li>
     *   <li>the selectors of {@code [SetVersion]} and {@code [AddBanner]} that name exactly the path;</li>
     *   <li>if the file name changes and the target of such a copy line is a folder or has the old file name, the
     *       target as the {@link TargetMode} says ({@link Rename#choice()} is then true);</li>
     *   <li>a line of {@code [Cleanup]} (without wildcards) that would remove a new target of these copy lines is
     *       removed, since the deployment would stop with an error.</li>
     * </ul>
     *
     * @param oldPath      old path relative to the project root ({@code \} or {@code /})
     * @param newPath      new path relative to the project root
     * @param folder       true if a folder was renamed or moved
     * @param mode         what happens to a target that depends on the file name
     * @param folderTarget is the target as written a folder (as the deployment decides it)?
     */
    public static @NotNull Rename rename(@NotNull CharSequence text, @NotNull String oldPath, @NotNull String newPath,
                                         boolean folder, @NotNull TargetMode mode,
                                         @NotNull Predicate<String> folderTarget) {
        return rename(text, oldPath, newPath, folder, mode, folderTarget, false);
    }

    /**
     * As {@link #rename(CharSequence, String, String, boolean, TargetMode, Predicate)}; {@code legacy}: the text of a
     * {@code settings.ahk-plugin.ini} of version 2.1, read with the old section names.
     */
    public static @NotNull Rename rename(@NotNull CharSequence text, @NotNull String oldPath, @NotNull String newPath,
                                         boolean folder, @NotNull TargetMode mode,
                                         @NotNull Predicate<String> folderTarget, boolean legacy) {
        String original = text.toString();
        String old = normalized(oldPath);
        String renamed = newPath.replace('/', '\\');
        while (renamed.endsWith("\\")) renamed = renamed.substring(0, renamed.length() - 1);
        if (old.isEmpty() || renamed.isEmpty()) return new Rename(original, List.of(), false, false, -1);
        // the executable compiled from a renamed script
        String oldExe = null;
        String newExe = null;
        if (!folder && old.endsWith(".ahk")) {
            oldExe = old.substring(0, old.length() - 4) + ".exe";
            int dot = renamed.lastIndexOf('.');
            newExe = (dot > renamed.lastIndexOf('\\') ? renamed.substring(0, dot) : renamed) + ".exe";
        }
        List<Line> lines = lines(original);
        String[] result = new String[lines.size()];
        String[] sections = new String[lines.size()];
        List<String[]> targets = new ArrayList<>();
        List<String[]> additions = new ArrayList<>();
        List<CleanupLine> cleanup = new ArrayList<>();
        boolean choice = false;
        boolean executable = false;
        int caretLine = -1;
        int caretColumn = 0;
        String section = "";
        String written = "";

        for (int i = 0; i < lines.size(); i++) {
            String raw = lines.get(i).text();
            result[i] = raw;
            String name = section(raw);
            if (name != null) {
                // a file of version 2.1: its sections under their current names
                section = AhkSettingsFormat.current(name, legacy);
                String header = raw.strip();
                if (!header.isEmpty() && header.charAt(0) == BOM) header = header.substring(1).strip();
                written = header.substring(1, header.length() - 1).strip();
                continue;
            }
            sections[i] = written;
            if (!isEntry(raw)) continue;
            int eq = raw.indexOf('=');
            if (eq <= 0) continue;
            Span key = key(raw, eq);
            Span value = value(raw, eq);
            if (section.equals("cleanup")) {
                cleanup.add(new CleanupLine(i, key.text(), value.text()));
                continue;
            }
            if (section.equals("setversion") || section.equals("addbanner")) {
                String replacement = renamedSelector(key.text(), old, renamed, folder);
                if (replacement != null) {
                    result[i] = raw.substring(0, key.start()) + replacement + raw.substring(key.end());
                }
                continue;
            }
            if (!section.equals("install") && !section.equals("installifmissing")) continue;
            String replacement = renamedSource(key.text(), old, renamed, folder);
            if (replacement == null && oldExe != null) {
                replacement = renamedSource(key.text(), oldExe, newExe, false);
                if (replacement != null) executable = true;
            }
            if (replacement == null) continue;
            String oldName = lastSegment(key.text());
            String newName = lastSegment(replacement);
            String target = value.text();
            boolean isFolder = !target.isEmpty() && folderTarget.test(target);
            boolean oldNameTarget = !isFolder && lastSegment(target).equalsIgnoreCase(oldName);
            String newTarget = target;
            String targetFolder = isFolder ? target : parent(target);
            String targetName = isFolder ? newName : lastSegment(target);
            if (!oldName.equalsIgnoreCase(newName) && (isFolder || oldNameTarget)) {
                choice = true;
                if (mode == TargetMode.KEEP_NAME && isFolder) {
                    newTarget = joined(target, oldName);
                    targetName = oldName;
                } else if (mode == TargetMode.RENAME && oldNameTarget) {
                    newTarget = parent(target) + newName;
                    targetName = newName;
                }
                if (mode == TargetMode.RENAME && section.equals("install")) {
                    additions.add(new String[]{oldName, targetFolder.isEmpty() ? "." : targetFolder});
                }
            }
            targets.add(new String[]{targetFolder, targetName});
            String prefix = raw.substring(0, key.start()) + replacement + raw.substring(key.end(), value.start());
            result[i] = prefix + newTarget + raw.substring(value.end());
            if (caretLine < 0) {
                caretLine = i;
                caretColumn = prefix.length();
            }
        }
        // [Cleanup] must not remove a target of the deployment
        for (CleanupLine line : cleanup) {
            if (line.selector().contains("*") || line.selector().contains("?")) continue;
            String path = targetKey(line.folder(), line.selector());
            if (targets.stream().anyMatch(t -> targetKey(t[0], t[1]).equals(path))) result[line.index()] = null;
        }
        List<LineEdit> edits = new ArrayList<>();
        for (int i = 0; i < lines.size(); i++) {
            String raw = lines.get(i).text();
            if (raw.equals(result[i])) continue;
            if (caretLine < 0) {
                caretLine = i;
                caretColumn = 0;
            }
            edits.add(new LineEdit(i + 1, raw.strip(), result[i] == null ? null : result[i].strip(), sections[i]));
        }
        StringBuilder joined = new StringBuilder();
        int caret = -1;
        for (int i = 0; i < lines.size(); i++) {
            if (result[i] == null) continue;
            if (i == caretLine) caret = joined.length() + caretColumn;
            joined.append(result[i]);
            if (i + 1 < lines.size()) joined.append(original, lines.get(i).end(), lines.get(i + 1).start());
        }
        String newText = joined.toString();
        for (String[] addition : additions) {
            String path = targetKey(addition[1], addition[0]);
            boolean present = cleanup.stream().anyMatch(c -> result[c.index()] != null
                    && targetKey(c.folder(), c.selector()).equals(path));
            String line = addition[0] + "=" + addition[1];
            if (present || edits.stream().anyMatch(e -> e.line() == 0 && line.equals(e.newText()))) continue;
            newText = withLine(newText, "Cleanup", line);
            edits.add(new LineEdit(0, null, line, "Cleanup"));
            caret = -1;
        }
        if (edits.isEmpty()) caret = -1;
        return new Rename(newText, edits, choice, executable, caret);
    }

    /** The key of the line before the {@code =} at {@code eq}, without surrounding blanks and quotes. */
    private static @NotNull Span key(@NotNull String raw, int eq) {
        return trimmed(raw, 0, eq);
    }

    /** The value of the line after the {@code =} at {@code eq}, without surrounding blanks and quotes. */
    private static @NotNull Span value(@NotNull String raw, int eq) {
        return trimmed(raw, eq + 1, raw.length());
    }

    /** The part of the line between the offsets without surrounding blanks and one pair of double quotes. */
    private static @NotNull Span trimmed(@NotNull String raw, int start, int end) {
        while (start < end && Character.isWhitespace(raw.charAt(start))) start++;
        while (end > start && Character.isWhitespace(raw.charAt(end - 1))) end--;
        if (end - start >= 2 && raw.charAt(start) == '"' && raw.charAt(end - 1) == '"') {
            start++;
            end--;
        }
        return new Span(start, end, raw.substring(start, end));
    }

    /** The last segment of a path ({@code \} or {@code /}). */
    private static @NotNull String lastSegment(@NotNull String path) {
        return path.substring(Math.max(path.lastIndexOf('\\'), path.lastIndexOf('/')) + 1);
    }

    /** The path up to and with its last separator, empty without separator. */
    private static @NotNull String parent(@NotNull String path) {
        return path.substring(0, Math.max(path.lastIndexOf('\\'), path.lastIndexOf('/')) + 1);
    }

    /**
     * The folder target with a file name: appended after a trailing separator, instead of a lone {@code .}, otherwise
     * after a separator ({@code /} if the target uses only {@code /}).
     */
    private static @NotNull String joined(@NotNull String folder, @NotNull String name) {
        if (folder.endsWith("\\") || folder.endsWith("/")) return folder + name;
        if (folder.equals(".")) return name;
        return folder + (folder.contains("/") && !folder.contains("\\") ? "/" : "\\") + name;
    }

    /** A target as written (folder and file name) for comparison: {@code \}, lower case, without {@code .} segments. */
    private static @NotNull String targetKey(@NotNull String folder, @NotNull String name) {
        String path = folder.replace('/', '\\');
        while (path.endsWith("\\")) path = path.substring(0, path.length() - 1);
        if (path.equals(".")) path = "";
        while (path.startsWith(".\\")) path = path.substring(2);
        path = path.replace("\\.\\", "\\");
        if (path.endsWith("\\.")) path = path.substring(0, path.length() - 2);
        String file = name.replace('/', '\\');
        return (path.isEmpty() ? file : path + "\\" + file).toLowerCase(Locale.ROOT);
    }

    /** The new source of a copy line, {@code null} if it does not name the path (wildcard names never match a file). */
    private static @Nullable String renamedSource(String key, String old, String renamed, boolean folder) {
        String path = normalized(key);
        if (!folder) {
            return path.equals(old) && !key.contains("*") && !key.contains("?") ? styled(key, renamed, "") : null;
        }
        String written = key.replace('/', '\\');
        if (written.startsWith("\\") || !path.startsWith(old + "\\")) return null;
        return styled(key, renamed, written.substring(old.length()));
    }

    /**
     * The new selector, {@code null} if it does not name exactly the path: no wildcards, and anchored (a separator at
     * the start or in the middle) unless the path lies in the project root.
     */
    private static @Nullable String renamedSelector(String key, String old, String renamed, boolean folder) {
        if (key.contains("*") || key.contains("?") || key.contains("[")) return null;
        String written = key.replace('/', '\\');
        String inner = written;
        while (inner.endsWith("\\")) inner = inner.substring(0, inner.length() - 1);
        boolean anchored = inner.contains("\\");
        String path = normalized(key);
        if (!anchored && old.contains("\\")) return null;
        int at = written.toLowerCase(Locale.ROOT).indexOf(old);
        if (at < 0) return null;
        if (path.equals(old)) return styled(key, written.substring(0, at) + renamed + written.substring(at + old.length()), "");
        if (folder && path.startsWith(old + "\\")) {
            return styled(key, written.substring(0, at) + renamed + written.substring(at + old.length()), "");
        }
        return null;
    }

    /** The new key with the rest appended, with {@code /} as separator if the key used only {@code /}. */
    private static String styled(String key, String path, String rest) {
        String result = path + rest;
        return key.contains("/") && !key.contains("\\") ? result.replace('\\', '/') : result;
    }

    /** The text of a settings file and how it is stored, to write it back the same way. */
    record Stored(@NotNull String text, @NotNull Charset charset, byte @NotNull [] bom) {
        /** Writes the text in the same encoding (with the same byte order mark). */
        void write(@NotNull Path file, @NotNull String newText) throws IOException {
            byte[] body = newText.getBytes(charset);
            byte[] all = new byte[bom.length + body.length];
            System.arraycopy(bom, 0, all, 0, bom.length);
            System.arraycopy(body, 0, all, bom.length, body.length);
            Files.write(file, all);
        }
    }

    /** Reads a settings file: UTF-16 with byte order mark, otherwise UTF-8 (a byte order mark is kept apart). */
    static @NotNull Stored read(@NotNull Path file) throws IOException {
        byte[] content = Files.readAllBytes(file);
        if (content.length >= 2 && (content[0] & 0xFF) == 0xFF && (content[1] & 0xFF) == 0xFE) {
            return new Stored(new String(content, 2, content.length - 2, StandardCharsets.UTF_16LE),
                    StandardCharsets.UTF_16LE, new byte[]{(byte) 0xFF, (byte) 0xFE});
        }
        if (content.length >= 2 && (content[0] & 0xFF) == 0xFE && (content[1] & 0xFF) == 0xFF) {
            return new Stored(new String(content, 2, content.length - 2, StandardCharsets.UTF_16BE),
                    StandardCharsets.UTF_16BE, new byte[]{(byte) 0xFE, (byte) 0xFF});
        }
        if (content.length >= 3 && (content[0] & 0xFF) == 0xEF && (content[1] & 0xFF) == 0xBB
                && (content[2] & 0xFF) == 0xBF) {
            return new Stored(new String(content, 3, content.length - 3, StandardCharsets.UTF_8),
                    StandardCharsets.UTF_8, new byte[]{(byte) 0xEF, (byte) 0xBB, (byte) 0xBF});
        }
        return new Stored(new String(content, StandardCharsets.UTF_8), StandardCharsets.UTF_8, new byte[0]);
    }
}
