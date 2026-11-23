package de.kumpewo.autohotkey.install;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The names of the settings file and the names of plugin version 2.1, which are still read until version 2.5.0. The
 * file name tells which names a file uses: {@code settings.ahk-v2-plugin.ini} ({@link AhkDeployConfig#FILE_NAME}) only
 * the new ones, {@code settings.ahk-plugin.ini} ({@link AhkDeployConfig#LEGACY_FILE_NAME}) only the old ones:
 * <table>
 *   <caption>Old and new names</caption>
 *   <tr><th>2.1</th><th>now</th></tr>
 *   <tr><td>{@code [Install]} (the options)</td><td>{@code [Deploy]}</td></tr>
 *   <tr><td>{@code [Overwrite]}</td><td>{@code [Install]}</td></tr>
 *   <tr><td>{@code [KeepExisting]}</td><td>{@code [InstallIfMissing]}</td></tr>
 *   <tr><td>{@code [ApplyCopyNotice]}</td><td>{@code [AddBanner]}</td></tr>
 *   <tr><td>{@code CopyNotice}</td><td>{@code Banner}</td></tr>
 *   <tr><td>{@code CompileBeforeInstall}</td><td>{@code Compile}</td></tr>
 * </table>
 * {@code [SetVersion]} and {@code [Cleanup]} keep their names. {@link #updated} gives the text of an old file with the
 * new names, for "Update Settings", which writes it as the new file.
 */
public final class AhkSettingsFormat {

    private AhkSettingsFormat() {
    }

    /** Old section name (lower case) → new section name as written. */
    private static final Map<String, String> SECTIONS = Map.of(
            "install", "Deploy",
            "overwrite", "Install",
            "keepexisting", "InstallIfMissing",
            "applycopynotice", "AddBanner");

    /** New section name (lower case) → old section name as written. */
    private static final Map<String, String> OLD_WRITTEN = Map.of(
            "deploy", "Install",
            "install", "Overwrite",
            "installifmissing", "KeepExisting",
            "addbanner", "ApplyCopyNotice");

    /** Old key name (lower case) → new key name as written. */
    private static final Map<String, String> KEYS = Map.of(
            "copynotice", "Banner",
            "compilebeforeinstall", "Compile");

    /** Sections with the same name in both versions (lower case). */
    private static final Set<String> SHARED = Set.of("setversion", "cleanup");

    /** The options of {@code [Install]} in version 2.1 and of {@code [Deploy]} now (lower case). */
    private static final Set<String> OPTIONS = Set.of("destination", "message", "copynotice", "requiresadmin",
            "generateoverview", "compilebeforeinstall", "banner", "compile");

    /** In comments: the old names of sections and keys, as whole words. */
    private static final Pattern COMMENT_NAMES = Pattern.compile(
            "\\[(Install|Overwrite|KeepExisting|ApplyCopyNotice)]|\\b(CopyNotice|CompileBeforeInstall)\\b",
            Pattern.CASE_INSENSITIVE);

    /** A section header: indent, name, rest. */
    private static final Pattern HEADER = Pattern.compile("^(\\uFEFF?\\s*)\\[\\s*([^]]*?)\\s*](.*)$");

    /**
     * The current name (lower case) of a section as written (lower case). In a file of version 2.1 the new name of an
     * old one, and an empty name for a name that only the new file knows (an unknown section there); otherwise
     * unchanged.
     */
    public static @NotNull String current(@NotNull String section, boolean legacy) {
        if (!legacy || SHARED.contains(section)) return section;
        String renamed = SECTIONS.get(section);
        return renamed == null ? "" : renamed.toLowerCase(Locale.ROOT);
    }

    /**
     * For a section (lower case) of the new file that only version 2.1 used ({@code [Overwrite]},
     * {@code [KeepExisting]}, {@code [ApplyCopyNotice]}): its new name as written, otherwise {@code null}.
     */
    public static @Nullable String newSection(@NotNull String section) {
        return section.equals("install") ? null : SECTIONS.get(section) == null ? null
                : "[" + SECTIONS.get(section) + "]";
    }

    /** Is the key (any case) an option of {@code [Deploy]} (old or new name)? In the new {@code [Install]} a mistake. */
    public static boolean isOldOption(@NotNull String key) {
        return OPTIONS.contains(key.toLowerCase(Locale.ROOT));
    }

    /**
     * The name of a section (current name) as the file writes it: the old name in a file of version 2.1 (for a line
     * added to the file), otherwise the current one.
     */
    public static @NotNull String written(@NotNull String section, boolean legacy) {
        String old = legacy ? OLD_WRITTEN.get(section.toLowerCase(Locale.ROOT)) : null;
        return old == null ? section : old;
    }

    /** The current name (lower case) of a key of the options (lower case): the new name of an old one. */
    public static @NotNull String currentKey(@NotNull String key) {
        String renamed = KEYS.get(key);
        return renamed == null ? key : renamed.toLowerCase(Locale.ROOT);
    }

    /** The current key name as written ({@code Banner}, {@code Compile}) for a lower-case name. */
    public static @NotNull String keyName(@NotNull String key) {
        for (String name : KEYS.values()) {
            if (name.equalsIgnoreCase(key)) return name;
        }
        return key;
    }

    /**
     * The text of a file of version 2.1 with the new names: section headers, the keys of the options and the names in
     * comments ({@code [Overwrite]}, {@code CopyNotice} …); everything else (values, order, comments, line separators)
     * stays.
     */
    public static @NotNull String updated(@NotNull String text) {
        StringBuilder result = new StringBuilder();
        Matcher separators = Pattern.compile("\\r\\n|\\n|\\r").matcher(text);
        int start = 0;
        String section = "";
        while (true) {
            boolean found = separators.find();
            int end = found ? separators.start() : text.length();
            String line = text.substring(start, end);
            String stripped = line.strip();
            if (!stripped.isEmpty() && stripped.charAt(0) == '﻿') stripped = stripped.substring(1).strip();
            Matcher header = HEADER.matcher(line);
            if (stripped.startsWith(";")) {
                line = renamedInComment(line);
            } else if (stripped.startsWith("[") && stripped.endsWith("]") && header.matches()) {
                section = header.group(2).toLowerCase(Locale.ROOT);
                String renamed = SECTIONS.get(section);
                if (renamed != null) line = header.group(1) + "[" + renamed + "]" + header.group(3);
            } else if (section.equals("install")) {
                line = renamedKey(line);
            }
            result.append(line);
            if (!found) break;
            result.append(separators.group());
            start = separators.end();
        }
        return result.toString();
    }

    /** A key line of the options with the new key name (quotes and blanks kept). */
    private static @NotNull String renamedKey(@NotNull String line) {
        int eq = line.indexOf('=');
        if (eq <= 0) return line;
        String key = line.substring(0, eq);
        String name = AhkDeployConfig.unquote(key.strip());
        String renamed = KEYS.get(name.toLowerCase(Locale.ROOT));
        if (renamed == null) return line;
        int at = key.indexOf(name);
        return key.substring(0, at) + renamed + key.substring(at + name.length()) + line.substring(eq);
    }

    /** A comment line with the old names replaced by the new ones (also the old file name). */
    private static @NotNull String renamedInComment(@NotNull String line) {
        Matcher m = COMMENT_NAMES.matcher(line);
        StringBuilder result = new StringBuilder();
        while (m.find()) {
            String replacement = m.group(1) != null
                    ? "[" + SECTIONS.get(m.group(1).toLowerCase(Locale.ROOT)) + "]"
                    : KEYS.get(m.group(2).toLowerCase(Locale.ROOT));
            m.appendReplacement(result, Matcher.quoteReplacement(replacement));
        }
        m.appendTail(result);
        return result.toString().replace(AhkDeployConfig.LEGACY_FILE_NAME, AhkDeployConfig.FILE_NAME);
    }
}
