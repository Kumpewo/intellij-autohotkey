package de.kumpewo.autohotkey.parser;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Evaluates "#Requires AutoHotkey ..." (https://www.autohotkey.com/docs/v2/lib/_Requires.htm) and gives the
 * language level ({@link AhkLanguageLevel}) it asks for.
 * At least version 2: ONE part of the form [>|>=|=][v]Major... with Major >= 2 is enough, e.g. "v2.0", "2",
 * ">=2.0-", "v2.0-rc.2", "=v2.1". No match: "v1.1", ">1.1", "<2", "64-bit" (alone).
 * Version 2.1: at least version 2 and one part (not starting with "<") asks for 2.1 or later ("v2.1-alpha.39",
 * ">=2.1", "v3"); otherwise 2.0.
 */
public final class AhkVersion {


    private static final Pattern ITEM = Pattern.compile("(?:>=?|=)?[vV]?(\\d+)(?:\\D.*)?");
    private static final Pattern BITNESS = Pattern.compile("(?i)(32|64)-bit");

    private AhkVersion() {
    }

    /**
     * Arguments of a #Requires directive (text after "#Requires").
     * @return null if they don't concern an AutoHotkey version (then the directive doesn't count), otherwise
     *         {@link AhkLanguageLevel#OTHER} (below version 2), {@link AhkLanguageLevel#V2_0} or
     *         {@link AhkLanguageLevel#V2_1}
     */
    public static @Nullable AhkLanguageLevel level(@NotNull CharSequence args) {
        Requirement requirement = requirement(args);
        if (requirement == null) return null;
        if (!requirement.v2()) return AhkLanguageLevel.OTHER;
        return requirement.v20() ? AhkLanguageLevel.V2_0 : AhkLanguageLevel.V2_1;
    }

    /** At least version 2 (see the class comment); null if the arguments don't concern an AutoHotkey version. */
    private static @Nullable Boolean requiresV2(@NotNull CharSequence args) {
        String[] items = args.toString().trim().split("[ \t]+");
        if (items.length == 0 || !"AutoHotkey".equalsIgnoreCase(items[0])) return null;
        boolean anyVersion = false;
        for (int i = 1; i < items.length; i++) {
            String item = items[i];
            if (item.isEmpty() || BITNESS.matcher(item).matches()) continue;
            anyVersion = true;
            Matcher m = ITEM.matcher(item);
            if (m.matches() && m.group(1).length() <= 9 && Integer.parseInt(m.group(1)) >= 2) return true;
        }
        // "#Requires AutoHotkey 64-bit" says nothing about the version
        return anyVersion ? Boolean.FALSE : null;
    }

    /**
     * What a script requires for choosing the interpreter.
     *
     * @param v2   true if at least version 2 is required
     * @param v20  true if version 2.0 is enough: at least version 2 and no part asks for 2.1 or later
     *             ({@code v2}, {@code v2.0}, {@code 2.0.18}, {@code >=2.0-}, {@code v2.0-rc.2}; not {@code v2.1-alpha})
     * @param bits 32 or 64 if the directive names the bitness ({@code 64-bit}), otherwise 0
     */
    public record Requirement(boolean v2, boolean v20, int bits) {
    }

    /** Version part with major and optional minor version: {@code [>|>=|=][v]2.1…}. */
    private static final Pattern MAJOR_MINOR = Pattern.compile("(?:>=?|=)?[vV]?(\\d{1,9})(?:\\.(\\d{1,9}))?(?:\\D.*)?");

    /**
     * Requirement of a #Requires directive (text after "#Requires"), or {@code null} if it does not concern an
     * AutoHotkey version.
     */
    public static @Nullable Requirement requirement(@NotNull CharSequence args) {
        Boolean v2 = requiresV2(args);
        if (v2 == null) return null;
        boolean v20 = v2;
        int bits = 0;
        String[] items = args.toString().trim().split("[ \t]+");
        for (int i = 1; i < items.length; i++) {
            Matcher bitness = BITNESS.matcher(items[i]);
            if (bitness.matches()) {
                bits = Integer.parseInt(bitness.group(1));
                continue;
            }
            Matcher m = MAJOR_MINOR.matcher(items[i]);
            if (!m.matches() || items[i].startsWith("<")) continue;
            int major = Integer.parseInt(m.group(1));
            int minor = m.group(2) == null ? 0 : Integer.parseInt(m.group(2));
            if (major > 2 || major == 2 && minor > 0) v20 = false;
        }
        return new Requirement(v2, v20, bits);
    }

    /** Version text: numbers separated by dots, optionally followed by {@code -} and a pre-release label. */
    private static final Pattern VERSION = Pattern.compile("[vV]?(\\d{1,9}(?:\\.\\d{1,9})*)(?:-([0-9A-Za-z.\\-]+))?");

    /**
     * Compares two AutoHotkey version texts like {@code 2.0.31}, {@code 2.1-alpha.39}, {@code 2.1.0} (optionally with
     * a leading {@code v}): numbers first (missing ones count as 0), then a version without pre-release label is
     * greater than one with it ({@code 2.1-alpha.39 < 2.1}), pre-release labels compare part by part (numeric parts
     * as numbers, numbers before words: {@code alpha.4 < alpha.39 < beta.1}), as AutoHotkey's {@code VerCompare}.
     *
     * @return negative, 0 or positive like {@link Comparable#compareTo}; {@code null} if one text is no version
     */
    public static @Nullable Integer compareVersions(@NotNull String a, @NotNull String b) {
        Matcher ma = VERSION.matcher(a.trim());
        Matcher mb = VERSION.matcher(b.trim());
        if (!ma.matches() || !mb.matches()) return null;
        String[] na = ma.group(1).split("\\.");
        String[] nb = mb.group(1).split("\\.");
        for (int i = 0; i < Math.max(na.length, nb.length); i++) {
            long x = i < na.length ? Long.parseLong(na[i]) : 0;
            long y = i < nb.length ? Long.parseLong(nb[i]) : 0;
            if (x != y) return x < y ? -1 : 1;
        }
        String pa = ma.group(2);
        String pb = mb.group(2);
        if (pa == null || pb == null) return pa == null ? (pb == null ? 0 : 1) : -1;
        String[] la = pa.split("\\.");
        String[] lb = pb.split("\\.");
        for (int i = 0; i < Math.min(la.length, lb.length); i++) {
            boolean da = la[i].matches("\\d{1,9}");
            boolean db = lb[i].matches("\\d{1,9}");
            int c = da && db ? Integer.compare(Integer.parseInt(la[i]), Integer.parseInt(lb[i]))
                    : da ? -1 : db ? 1 : la[i].compareToIgnoreCase(lb[i]);
            if (c != 0) return c < 0 ? -1 : 1;
        }
        return Integer.compare(la.length, lb.length);
    }

    /** True if the version text asks for 2.1 or later ({@code 2.1-alpha.39}, {@code v2.1}, {@code 3.0}). */
    public static boolean atLeastV21(@NotNull String version) {
        Integer c = compareVersions(version, "2.1-0");
        return c != null && c >= 0;
    }

    /** {@code #Requires} line: directive at the start of a line, arguments up to a comment ({@code  ;…}). */
    private static final Pattern REQUIRES_LINE =
            Pattern.compile("(?im)^\\uFEFF?[ \\t]*#Requires[ \\t]+(.*?)(?:[ \\t]+;.*)?[ \\t]*$");

    /**
     * Requirement of the first {@code #Requires} line with a version in the script text (block comments are not
     * recognized), or {@code null}.
     */
    public static @Nullable Requirement scriptRequirement(@NotNull CharSequence text) {
        Matcher matcher = REQUIRES_LINE.matcher(text);
        while (matcher.find()) {
            Requirement requirement = requirement(matcher.group(1));
            if (requirement != null) return requirement;
        }
        return null;
    }
}
