package de.kumpewo.autohotkey.debug;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * Name paths, the only expressions the AutoHotkey engine can evaluate ({@code property_get -n}): a name followed by
 * members and indexes, e.g. {@code obj.key}, {@code obj.nested.deep[2]}, {@code m["key"]}, {@code m['key']},
 * {@code arr[-1]}. {@link #normalize} checks the text and returns the form for DBGp: without spaces, single quotes
 * as double quotes, hexadecimal indexes as decimal numbers (the engine reads no hex).
 */
public final class AhkNamePath {

    private AhkNamePath() {
    }

    /** The path in DBGp form, or {@code null} if the text is not a name path. */
    public static @Nullable String normalize(@NotNull String text) {
        String s = text.trim();
        StringBuilder out = new StringBuilder();
        int i = name(s, 0, out);
        if (i < 0) return null;
        while (i < s.length()) {
            char c = s.charAt(i);
            if (c == '.') {
                out.append('.');
                i = name(s, i + 1, out);
                if (i < 0) return null;
            } else if (c == '[') {
                i = index(s, i + 1, out);
                if (i < 0) return null;
            } else {
                return null;
            }
        }
        return out.toString();
    }

    /** True if the text is a name path. */
    public static boolean isPath(@NotNull String text) {
        return normalize(text) != null;
    }

    /** Reads a name at {@code i} into {@code out}; returns the end or -1. */
    private static int name(@NotNull String s, int i, @NotNull StringBuilder out) {
        if (i >= s.length() || !(Character.isLetter(s.charAt(i)) || s.charAt(i) == '_')) return -1;
        int start = i;
        while (i < s.length() && (Character.isLetterOrDigit(s.charAt(i)) || s.charAt(i) == '_')) i++;
        out.append(s, start, i);
        return i;
    }

    /** Reads {@code number]} or {@code "string"]} after {@code [}; returns the end after {@code ]} or -1. */
    private static int index(@NotNull String s, int i, @NotNull StringBuilder out) {
        i = skipSpaces(s, i);
        if (i >= s.length()) return -1;
        char c = s.charAt(i);
        String key;
        if (c == '"' || c == '\'') {
            int end = s.indexOf(c, i + 1);
            if (end < 0) return -1;
            String content = s.substring(i + 1, end);
            if (content.indexOf('"') >= 0 || content.indexOf('`') >= 0) return -1;
            key = "\"" + content + "\"";
            i = end + 1;
        } else {
            int start = i;
            if (c == '-' || c == '+') i++;
            while (i < s.length() && (Character.isLetterOrDigit(s.charAt(i)) || s.charAt(i) == '.')) i++;
            key = number(s.substring(start, i));
            if (key == null) return -1;
        }
        i = skipSpaces(s, i);
        if (i >= s.length() || s.charAt(i) != ']') return -1;
        out.append('[').append(key).append(']');
        return i + 1;
    }

    /** Decimal form of an integer (also hex) or a float literal, or {@code null}. */
    static @Nullable String number(@NotNull String text) {
        String t = text.startsWith("+") ? text.substring(1) : text;
        boolean negative = t.startsWith("-");
        String digits = negative ? t.substring(1) : t;
        if (digits.matches("0[xX][0-9a-fA-F]{1,15}")) {
            long value = Long.parseLong(digits.substring(2), 16);
            return Long.toString(negative ? -value : value);
        }
        if (digits.matches("\\d{1,18}")) return (negative ? "-" : "") + Long.parseLong(digits);
        if (digits.matches("(\\d+\\.\\d*|\\.\\d+)([eE][-+]?\\d+)?|\\d+[eE][-+]?\\d+")) return t;
        return null;
    }

    /** Position after spaces and tabs. */
    private static int skipSpaces(@NotNull String s, int i) {
        while (i < s.length() && (s.charAt(i) == ' ' || s.charAt(i) == '\t')) i++;
        return i;
    }
}
