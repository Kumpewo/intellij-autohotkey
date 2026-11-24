package de.kumpewo.autohotkey.install;

import de.kumpewo.autohotkey.compile.AhkCompiler;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.io.ByteArrayOutputStream;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The version of a script for "Set Version" ({@link AhkSetVersion}): in a script with {@code ;@Ahk2Exe-Base} the
 * compiler directive {@value #DIRECTIVE_KEYWORD} (file and product version of the executable), in any other AutoHotkey
 * file the plain comment {@value #TAG_KEYWORD}: Ahk2Exe also obeys the directives of included files, and the last
 * version wins, so a directive in a library would replace the version of the program ({@link #keyword}). Both are read
 * in any file, as Ahk2Exe reads its directive: the trimmed line starts with the keyword (any case), followed by
 * whitespace; a comment {@code  ; ...} after whitespace is not part of the value; the value is trimmed, quotes belong
 * to it. Lines in block comments {@code /* ... * /} and between {@code ;@Ahk2Exe-IgnoreBegin} and
 * {@code ;@Ahk2Exe-IgnoreEnd} are ignored, as by Ahk2Exe. Also the next version ({@link #bump}) and the tag name
 * ({@link #tagName}) and the order of versions ({@link #compare}).
 */
public final class AhkVersionDirective {

    /** The compiler directive, for scripts with {@code ;@Ahk2Exe-Base}. */
    static final String DIRECTIVE_KEYWORD = ";@Ahk2Exe-SetVersion";

    /** The comment that Ahk2Exe does not read, for all other AutoHotkey files. */
    static final String TAG_KEYWORD = ";@SetVersion";

    /** Directive or comment with the value (group 1 = the rest of the line after the whitespace). */
    private static final Pattern DIRECTIVE =
            Pattern.compile("(?is)^[ \\t]*;(?:@Ahk2Exe-|[ \\t]*@)SetVersion(?:[ \\t]+(.*))?$");

    /** A comment after the value: whitespace and {@code ;}. */
    private static final Pattern COMMENT = Pattern.compile("[ \\t]+;");

    /** Numbers at the start of a version: optional {@code v} (dropped), then up to four numbers separated by points. */
    private static final Pattern NUMBERS = Pattern.compile("^[vV]?(\\d+)(?:\\.(\\d+))?(?:\\.(\\d+))?(?:\\.(\\d+))?");

    /**
     * What a new version must not contain: a leading {@code v} (unusual in Windows versions), whitespace, {@code ,}
     * (argument separator of Ahk2Exe), {@code %}, {@code ;}, {@code `}.
     */
    private static final Pattern INVALID = Pattern.compile("^[vV]|[\\s,%;`]");

    private AhkVersionDirective() {
    }

    /**
     * One version line.
     *
     * @param line       0-based line number
     * @param valueStart offset of the value in the text (for an empty value: where it would start)
     * @param valueEnd   end offset of the value
     * @param value      the value, empty if there is none
     */
    public record Line(int line, int valueStart, int valueEnd, @NotNull String value) {
        /** Is the value computed by Ahk2Exe ({@code %A_PriorLine~...%}, {@code %U_Name%})? */
        public boolean computed() {
            return value.contains("%");
        }
    }

    /**
     * The version lines of a file: in an AutoHotkey file those of {@link #find}, in any other file the lines
     * {@code <start> @SetVersion <value> [<end>]} of its comment style (each start of
     * {@link AhkFileRules#readStarts}), with the value up to whitespace or the end of the comment.
     */
    public static @NotNull List<Line> findIn(@NotNull String fileName, @NotNull CharSequence text,
                                             @Nullable AhkFileRules.Style style) {
        if (AhkFileRules.isAutoHotkey(fileName) || style == null) return find(text);
        String end = style.end() == null ? "" : "|" + java.util.regex.Pattern.quote(style.end());
        StringBuilder starts = new StringBuilder();
        for (String start : AhkFileRules.readStarts(style)) {
            if (!starts.isEmpty()) starts.append('|');
            starts.append(java.util.regex.Pattern.quote(start));
        }
        java.util.regex.Pattern tagged = java.util.regex.Pattern.compile("(?im)^\\uFEFF?[ \\t]*(?:"
                + starts + ")[ \\t]*@SetVersion(?:[ \\t]+(.*?))?[ \\t]*(?:$" + end + ")");
        List<Line> result = new ArrayList<>();
        java.util.regex.Matcher m = tagged.matcher(text);
        while (m.find()) {
            int line = 0;
            for (int i = 0; i < m.start(); i++) if (text.charAt(i) == '\n') line++;
            String value = m.group(1) == null ? "" : m.group(1);
            int start = m.group(1) == null ? m.end() : m.start(1);
            result.add(new Line(line, start, start + value.length(), value));
        }
        return result;
    }

    /** The keyword for an AutoHotkey file with this text: the directive only in a script with a base directive. */
    static @NotNull String keyword(@NotNull CharSequence text) {
        return AhkCompiler.hasBaseDirective(text) ? DIRECTIVE_KEYWORD : TAG_KEYWORD;
    }

    /** Start and end of the keyword of a line of {@link #find}, from {@code ;} to {@code SetVersion}. */
    static int @NotNull [] keywordRange(@NotNull CharSequence text, @NotNull Line line) {
        int start = line.valueStart();
        while (start > 0 && text.charAt(start - 1) != '\n' && text.charAt(start - 1) != '\r') start--;
        while (text.charAt(start) != ';') start++;
        int end = start + 1;
        while (text.charAt(end) == ' ' || text.charAt(end) == '\t') end++;
        while (end < line.valueStart() && text.charAt(end) != ' ' && text.charAt(end) != '\t') end++;
        return new int[]{start, end};
    }

    /**
     * The content of an AutoHotkey file for its deployed copy: without a base directive each
     * {@value #DIRECTIVE_KEYWORD} becomes {@value #TAG_KEYWORD}; everything else stays byte by byte, and so does the
     * number of lines.
     */
    static byte @NotNull [] tagged(byte @NotNull [] content) {
        Charset charset = StandardCharsets.ISO_8859_1;
        int bom = 0;
        if (content.length >= 2 && (content[0] & 0xFF) == 0xFF && (content[1] & 0xFF) == 0xFE) {
            charset = StandardCharsets.UTF_16LE;
            bom = 2;
        } else if (content.length >= 2 && (content[0] & 0xFF) == 0xFE && (content[1] & 0xFF) == 0xFF) {
            charset = StandardCharsets.UTF_16BE;
            bom = 2;
        } else if (content.length >= 3 && (content[0] & 0xFF) == 0xEF && (content[1] & 0xFF) == 0xBB
                && (content[2] & 0xFF) == 0xBF) {
            bom = 3;
        }
        String text = new String(content, bom, content.length - bom, charset);
        if (AhkCompiler.hasBaseDirective(text)) return content;
        ByteArrayOutputStream out = new ByteArrayOutputStream(content.length);
        int width = charset == StandardCharsets.ISO_8859_1 ? 1 : 2;
        int done = 0;
        for (Line line : find(text)) {
            int[] range = keywordRange(text, line);
            if (!text.substring(range[0], range[1]).equalsIgnoreCase(DIRECTIVE_KEYWORD)) continue;
            int start = bom + range[0] * width;
            out.write(content, done, start - done);
            out.writeBytes(TAG_KEYWORD.getBytes(charset));
            done = bom + range[1] * width;
        }
        if (done == 0) return content;
        out.write(content, done, content.length - done);
        return out.toByteArray();
    }

    /** All version lines (directive or comment) outside of block comments and ignored sections, in file order. */
    public static @NotNull List<Line> find(@NotNull CharSequence text) {
        List<Line> result = new ArrayList<>();
        boolean blockComment = false;
        boolean ignore = false;
        int start = 0;
        int lineNumber = 0;
        int length = text.length();
        while (start <= length) {
            int end = start;
            while (end < length && text.charAt(end) != '\n' && text.charAt(end) != '\r') end++;
            String line = text.subSequence(start, end).toString();
            int lineStart = start;
            if (lineNumber == 0 && line.startsWith("﻿")) {
                line = line.substring(1);
                lineStart++;
            }
            String trimmed = line.strip();
            String lower = trimmed.toLowerCase(Locale.ROOT);
            if (blockComment) {
                if (trimmed.startsWith("*/")) blockComment = false;
            } else if (ignore) {
                if (lower.startsWith(";@ahk2exe-ignoreend")) ignore = false;
            } else if (lower.startsWith(";@ahk2exe-ignorebegin")) {
                ignore = true;
            } else if (trimmed.startsWith("/*")) {
                // "/*@Ahk2Exe-Keep" blocks are code for Ahk2Exe; a comment closed in the same line ends at once
                if (!lower.startsWith("/*@ahk2exe-keep") && !(trimmed.length() > 2 && trimmed.endsWith("*/"))) {
                    blockComment = true;
                }
            } else {
                Matcher m = DIRECTIVE.matcher(line);
                if (m.matches()) {
                    int valueStart;
                    int valueEnd;
                    if (m.group(1) == null) {
                        valueStart = valueEnd = lineStart + line.length();
                    } else {
                        String rest = m.group(1);
                        Matcher comment = COMMENT.matcher(rest);
                        int restEnd = comment.find() ? comment.start() : rest.length();
                        String value = rest.substring(0, restEnd);
                        int lead = value.length() - value.stripLeading().length();
                        valueStart = lineStart + m.start(1) + lead;
                        valueEnd = valueStart + value.strip().length();
                    }
                    result.add(new Line(lineNumber, valueStart, valueEnd,
                            text.subSequence(valueStart, valueEnd).toString()));
                }
            }
            if (end >= length) break;
            start = end + (text.charAt(end) == '\r' && end + 1 < length && text.charAt(end + 1) == '\n' ? 2 : 1);
            lineNumber++;
        }
        return result;
    }

    /** Parts of a version that {@link #bump} increases. */
    public enum Part { MAJOR, MINOR, PATCH }

    /**
     * The next version: the numbers at the start of the version (at least three, up to four, missing ones are 0) with
     * the part increased and the following parts set to 0; a text after the numbers ({@code -beta}) and a leading
     * {@code v} are dropped. {@code 1.2.3-dev} → patch {@code 1.2.4}, minor {@code 1.3.0}, major {@code 2.0.0};
     * {@code 1.2.3.4} → patch {@code 1.2.4.0}. {@code null} if the version does not start with a number.
     */
    public static @Nullable String bump(@NotNull String version, @NotNull Part part) {
        Matcher m = NUMBERS.matcher(version.strip());
        if (!m.find()) return null;
        int count = m.group(4) != null ? 4 : 3;
        long[] numbers = new long[count];
        for (int i = 0; i < count; i++) {
            String group = m.group(i + 1);
            numbers[i] = group == null ? 0 : Long.parseLong(group);
        }
        int index = part.ordinal();
        numbers[index]++;
        for (int i = index + 1; i < count; i++) numbers[i] = 0;
        StringBuilder result = new StringBuilder();
        for (int i = 0; i < count; i++) {
            if (i > 0) result.append('.');
            result.append(numbers[i]);
        }
        return result.toString();
    }

    /**
     * The proposal of "Set Version..." for the part: {@link #bump} of the current version; without one (no file has a
     * version yet) from {@code 0.0.0}, so {@code 0.0.1}, {@code 0.1.0} or {@code 1.0.0}.
     */
    public static @Nullable String next(@NotNull String current, @NotNull Part part) {
        return bump(current.isBlank() ? "0.0.0" : current, part);
    }

    /**
     * Is the text usable as a new version (not empty, no leading {@code v}, no whitespace, {@code ,}, {@code %},
     * {@code ;} or {@code `})?
     */
    public static boolean isValidVersion(@NotNull String version) {
        return !version.isEmpty() && !INVALID.matcher(version).find();
    }

    /** Tag name {@code v<version>} (a leading {@code v} of the version is not doubled). */
    public static @NotNull String tagName(@NotNull String version) {
        String v = version.startsWith("v") || version.startsWith("V") ? version.substring(1) : version;
        return "v" + v;
    }

    /**
     * Compares two versions by the numbers at their start (missing numbers are 0), then as text; negative if
     * {@code a} is lower. A version without numbers is lower than one with numbers.
     */
    public static int compare(@NotNull String a, @NotNull String b) {
        long[] x = numbers(a);
        long[] y = numbers(b);
        if (x == null || y == null) return x == null ? (y == null ? a.compareTo(b) : -1) : 1;
        for (int i = 0; i < 4; i++) {
            if (x[i] != y[i]) return Long.compare(x[i], y[i]);
        }
        return a.compareTo(b);
    }

    /** The four numbers at the start of the version (missing ones 0), {@code null} without a number. */
    private static long @Nullable [] numbers(@NotNull String version) {
        Matcher m = NUMBERS.matcher(version.strip());
        if (!m.find()) return null;
        long[] result = new long[4];
        for (int i = 0; i < 4; i++) {
            String group = m.group(i + 1);
            try {
                result[i] = group == null ? 0 : Long.parseLong(group);
            } catch (NumberFormatException e) {
                result[i] = Long.MAX_VALUE;
            }
        }
        return result;
    }
}
