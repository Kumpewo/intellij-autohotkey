package de.kumpewo.autohotkey.install;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The version of a script for "Set Version" ({@link AhkSetVersion}): the compiler directive
 * {@code ;@Ahk2Exe-SetVersion 1.2.0}, which sets file and product version of the compiled executable. The line is read
 * as Ahk2Exe reads it: the trimmed line starts with {@code ;@Ahk2Exe-} directly followed by the directive name (any
 * case) and whitespace; a comment {@code  ; ...} after whitespace is not part of the value; the value is trimmed,
 * quotes belong to it. Lines in block comments {@code /* ... * /} and between {@code ;@Ahk2Exe-IgnoreBegin} and
 * {@code ;@Ahk2Exe-IgnoreEnd} are ignored, as by Ahk2Exe. Also the next version ({@link #bump}) and the tag name
 * ({@link #tagName}) and the order of versions ({@link #compare}).
 */
public final class AhkVersionDirective {

    /** {@code ;@Ahk2Exe-SetVersion value} (group 1 = the rest of the line after the whitespace). */
    private static final Pattern DIRECTIVE =
            Pattern.compile("(?i)^[ \\t]*;@Ahk2Exe-SetVersion(?:[ \\t]+(.*))?$");

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
     * One {@code ;@Ahk2Exe-SetVersion} line.
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

    /** All {@code ;@Ahk2Exe-SetVersion} lines that Ahk2Exe reads, in file order. */
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
