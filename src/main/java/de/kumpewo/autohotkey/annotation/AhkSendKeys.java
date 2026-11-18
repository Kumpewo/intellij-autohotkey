package de.kumpewo.autohotkey.annotation;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.function.Predicate;

/**
 * Key syntax of the Send functions in a text (help: lib/Send.htm), as used in the replacement text of hotstrings and
 * in the strings passed to Send, SendInput, ControlSend and their variants:
 * <ul>
 *   <li>keys in braces: key names ({@code {Enter}}, {@code {Esc}}), a single character ({@code {a}}, {@code {!}},
 *       {@code {{}}, {@code {}}}), {@code {F1}}, {@code {vk41}}, {@code {sc01E}}, {@code {vk41sc01E}},
 *       {@code {U+20AC}}, {@code {ASC 0133}}, {@code {Click ...}}; optionally followed by a repeat count or
 *       {@code down}, {@code up}, {@code downTemp}, {@code downR} ({@code {Tab 3}}, {@code {Shift down}})</li>
 *   <li>the modes {@code {Raw}} and {@code {Text}} (everything after them is literal) and {@code {Blind}}
 *       (optionally followed by modifier symbols, only at the beginning)</li>
 *   <li>modifier symbols {@code ^ + ! #} together with the key they apply to ({@code ^c}, {@code +{Tab}})</li>
 * </ul>
 * Escape sequences ({@code `n}) are skipped; they keep their own color. A brace without a closing brace on the same
 * line is not key syntax. Unknown names and options in braces are reported, because AutoHotkey ignores them silently.
 */
public final class AhkSendKeys {

    /** Kind of a found part. */
    public enum Kind {
        /** Valid key syntax (colored). */
        KEY,
        /** Unknown key name in braces (warning, uncolored). */
        UNKNOWN_KEY,
        /** Unknown option after a key name in braces (warning, uncolored). */
        UNKNOWN_OPTION,
        /** {Blind} that is not at the beginning (warning, uncolored). */
        BLIND_NOT_FIRST
    }

    /**
     * Part of the text: range relative to the text, kind, and for warnings the text the message names (key name or
     * option).
     */
    public record Part(int start, int end, @NotNull Kind kind, @NotNull String name) {
    }

    /** Modifier symbols: Ctrl, Shift, Alt, Win. */
    private static final String MODIFIERS = "^+!#";

    /** Options after a key name besides a repeat count. */
    private static final List<String> KEY_OPTIONS = List.of("down", "up", "downtemp", "downr");

    private AhkSendKeys() {
    }

    /**
     * Finds the key syntax in {@code text} between {@code start} (inclusive) and {@code end} (exclusive).
     *
     * @param isKeyName tells whether a word is a known key name ({@code Enter}, {@code F1}), regardless of case
     * @return the parts in text order
     */
    public static @NotNull List<Part> parse(@NotNull CharSequence text, int start, int end,
                                            @NotNull Predicate<String> isKeyName) {
        List<Part> parts = new ArrayList<>();
        int i = start;
        while (i < end) {
            char c = text.charAt(i);
            if (c == '`') {
                i += 2;   // escape sequence: the backtick and the next character
            } else if (c == '{') {
                int close = closingBrace(text, i, end);
                if (close < 0) {
                    i++;
                    continue;
                }
                Part part = braced(text, i, close + 1, i == start, isKeyName);
                parts.add(part);
                i = close + 1;
                if (part.kind() == Kind.KEY && isLiteralMode(text.subSequence(part.start(), part.end()))) {
                    break;   // {Raw} or {Text}: the rest is literal
                }
            } else if (MODIFIERS.indexOf(c) >= 0) {
                int j = i;
                while (j < end && MODIFIERS.indexOf(text.charAt(j)) >= 0) j++;
                if (j >= end) break;
                char next = text.charAt(j);
                if (next == '{' || next == '`') {
                    // modifiers before a key in braces or an escape sequence: the key is handled on its own
                    if (next == '`' || closingBrace(text, j, end) >= 0) parts.add(new Part(i, j, Kind.KEY, ""));
                    i = j;
                } else if (next == '\r' || next == '\n') {
                    i = j;
                } else {
                    int keyEnd = j + Character.charCount(Character.codePointAt(text, j));
                    parts.add(new Part(i, Math.min(keyEnd, end), Kind.KEY, ""));
                    i = keyEnd;
                }
            } else {
                i++;
            }
        }
        return parts;
    }

    /** Whether the braced part (with braces) is {Raw} or {Text}. */
    private static boolean isLiteralMode(CharSequence braced) {
        String content = braced.subSequence(1, braced.length() - 1).toString().toLowerCase(Locale.ROOT);
        return content.equals("raw") || content.equals("text");
    }

    /**
     * Index of the "}" that closes the "{" at {@code open}, or -1 if there is none on the same line.
     * {@code {}}} is the brace key, {@code {{}} the opening brace.
     */
    private static int closingBrace(CharSequence text, int open, int end) {
        if (open + 2 < end && text.charAt(open + 1) == '}' && text.charAt(open + 2) == '}') return open + 2;
        for (int k = open + 1; k < end; k++) {
            char c = text.charAt(k);
            if (c == '\r' || c == '\n') return -1;
            if (c == '}') return k;
        }
        return -1;
    }

    /** Checks the braced part from {@code start} to {@code end} (with braces). */
    private static Part braced(CharSequence text, int start, int end, boolean first, Predicate<String> isKeyName) {
        String content = text.subSequence(start + 1, end - 1).toString();
        if (content.isEmpty()) return new Part(start, end, Kind.UNKNOWN_KEY, "");
        // a single character is a key, also a space or a brace
        if (content.codePointCount(0, content.length()) == 1) return new Part(start, end, Kind.KEY, content);
        int space = indexOfBlank(content);
        String name = space < 0 ? content : content.substring(0, space);
        String option = space < 0 ? "" : content.substring(space).trim();
        String lower = name.toLowerCase(Locale.ROOT);

        if (lower.equals("click")) return new Part(start, end, Kind.KEY, name);
        if (lower.equals("raw") || lower.equals("text")) {
            return option.isEmpty() ? new Part(start, end, Kind.KEY, name)
                    : new Part(start, end, Kind.UNKNOWN_OPTION, option);
        }
        if (lower.matches("blind[!#^+]*")) {
            if (!option.isEmpty()) return new Part(start, end, Kind.UNKNOWN_OPTION, option);
            return first ? new Part(start, end, Kind.KEY, name) : new Part(start, end, Kind.BLIND_NOT_FIRST, name);
        }
        if (lower.equals("asc")) {
            return option.matches("[0-9]+") ? new Part(start, end, Kind.KEY, name)
                    : new Part(start, end, Kind.UNKNOWN_OPTION, option);
        }
        boolean known = name.codePointCount(0, name.length()) == 1
                || lower.matches("u\\+[0-9a-f]+")
                || lower.matches("vk[0-9a-f]+(sc[0-9a-f]+)?|sc[0-9a-f]+")
                || isKeyName.test(name);
        if (!known) return new Part(start, end, Kind.UNKNOWN_KEY, name);
        if (!option.isEmpty() && !option.matches("[0-9]+")
                && !KEY_OPTIONS.contains(option.toLowerCase(Locale.ROOT))) {
            return new Part(start, end, Kind.UNKNOWN_OPTION, option);
        }
        return new Part(start, end, Kind.KEY, name);
    }

    /** Index of the first space or tab, or -1. */
    private static int indexOfBlank(String s) {
        for (int k = 0; k < s.length(); k++) {
            if (s.charAt(k) == ' ' || s.charAt(k) == '\t') return k;
        }
        return -1;
    }

    /**
     * Whether hotstring options ({@code *0 C R}, {@code T0}) switch the raw or text mode on ({@code TRUE}) or off
     * ({@code FALSE}); {@code null} if they say nothing about it. The last mention wins ({@code R0} and {@code T0}
     * both switch the mode off).
     */
    public static @Nullable Boolean literalOption(@NotNull String options) {
        Boolean result = null;
        String s = options.toUpperCase(Locale.ROOT);
        int i = 0;
        while (i < s.length()) {
            char c = s.charAt(i++);
            if (c == 'S' && i < s.length() && "IEP".indexOf(s.charAt(i)) >= 0) {
                i++;   // SI, SE, SP: send mode
                continue;
            }
            int digits = i;
            while (i < s.length() && (Character.isDigit(s.charAt(i)) || s.charAt(i) == '-')) i++;
            if (c == 'R' || c == 'T') result = !s.substring(digits, i).equals("0");
        }
        return result;
    }
}
