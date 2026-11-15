package de.kumpewo.autohotkey.parser;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Evaluates "#Requires AutoHotkey ..." (https://www.autohotkey.com/docs/v2/lib/_Requires.htm).
 * The only question is whether at least version 2 is required; minor version, release candidate etc. don't matter.
 * ONE part of the form [>|>=|=][v]Major... with Major >= 2 is enough, e.g. "v2.0", "2", ">=2.0-", "v2.0-rc.2",
 * "=v2.1". No match: "v1.1", ">1.1", "<2", "64-bit" (alone).
 */
public final class AhkVersion {


    private static final Pattern ITEM = Pattern.compile("(?:>=?|=)?[vV]?(\\d+)(?:\\D.*)?");
    private static final Pattern BITNESS = Pattern.compile("(?i)(32|64)-bit");

    private AhkVersion() {
    }

    /**
     * Arguments of a #Requires directive (text after "#Requires").
     * @return null if they don't concern an AutoHotkey version (then the directive doesn't count),
     *         otherwise true/false = at least version 2 is required
     */
    public static @Nullable Boolean requiresV2(@NotNull CharSequence args) {
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
     * @param v20  true if version 2.0 is enough: at least version 2 and no part asks for 2.1 or later
     *             ({@code v2}, {@code v2.0}, {@code 2.0.18}, {@code >=2.0-}, {@code v2.0-rc.2}; not {@code v2.1-alpha})
     * @param bits 32 or 64 if the directive names the bitness ({@code 64-bit}), otherwise 0
     */
    public record Requirement(boolean v20, int bits) {
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
        return new Requirement(v20, bits);
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
