package de.kumpewo.autohotkey.install;

import de.kumpewo.autohotkey.AhkBundle;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Contents of a {@code settings.ahk-plugin.ini} next to a script (the tool folder), read by {@link #parse}. It holds
 * the settings of the installer ({@link AhkInstaller}) and of "Set Version" ({@link AhkSetVersion}):
 * <ul>
 *   <li>{@code [Install]}: {@code Destination}, {@code Message}, {@code RequiresAdmin}, {@code GenerateOverview}
 *       (default true), {@code CompileBeforeInstall}; missing switches are false.</li>
 *   <li>{@code [Overwrite]} and {@code [KeepExisting]}: lines {@code source=target}; the source is relative to the
 *       tool folder (wildcards {@code *} and {@code ?} in the file name), the target absolute or relative to
 *       {@code Destination}. {@code [KeepExisting]} only copies if the target does not exist yet.</li>
 *   <li>{@code [Version]}: {@code TagPrefix}, the first part of the version tags {@code <TagPrefix>/v<version>}.</li>
 * </ul>
 * Comments are lines starting with {@code ;} (leading whitespace allowed); a {@code ;} later in a line is text.
 * Values are trimmed, and one pair of enclosing double quotes is removed (like Windows INI files). Section and key
 * names are case-insensitive. Mistakes (unknown section or key, line without {@code =}, a key or source twice in a
 * section, a switch that is neither true nor false) are collected in {@link #errors()}; resolving paths and checking
 * files is the job of {@link AhkInstallPlan}.
 */
public final class AhkInstallConfig {

    /** File name of the configuration in the tool folder. */
    public static final String FILE_NAME = "settings.ahk-plugin.ini";

    /**
     * One line {@code source=target} of {@code [Overwrite]} or {@code [KeepExisting]}.
     *
     * @param source       source as written (relative to the tool folder)
     * @param target       target as written (environment variables not yet resolved)
     * @param keepExisting true for {@code [KeepExisting]}
     * @param line         1-based line in the file
     */
    public record Entry(@NotNull String source, @NotNull String target, boolean keepExisting, int line) {
    }

    private @Nullable String destination;
    private @Nullable String message;
    private boolean requiresAdmin;
    private boolean generateOverview = true;
    private @Nullable String tagPrefix;
    private boolean compile;
    private final List<Entry> entries = new ArrayList<>();
    private final List<String> errors = new ArrayList<>();

    private AhkInstallConfig() {
    }

    /** {@code Destination} as written, {@code null} if missing or empty. */
    public @Nullable String destination() {
        return destination;
    }

    /** {@code Message} as written, {@code null} if missing or empty. */
    public @Nullable String message() {
        return message;
    }

    /** {@code RequiresAdmin}: copy in one elevated process. */
    public boolean requiresAdmin() {
        return requiresAdmin;
    }

    /** {@code GenerateOverview} (default true): show the copy sections in the confirmation. */
    public boolean generateOverview() {
        return generateOverview;
    }

    /** {@code TagPrefix} of {@code [Version]} as written, {@code null} if missing or empty. */
    public @Nullable String tagPrefix() {
        return tagPrefix;
    }

    /** {@code CompileBeforeInstall}: compile the script first. */
    public boolean compile() {
        return compile;
    }

    /** The copy lines in file order. */
    public @NotNull List<Entry> entries() {
        return entries;
    }

    /** Mistakes in the file, empty if it is valid. */
    public @NotNull List<String> errors() {
        return errors;
    }

    /** Reads the text of a {@code settings.ahk-plugin.ini}. */
    public static @NotNull AhkInstallConfig parse(@NotNull String text) {
        AhkInstallConfig config = new AhkInstallConfig();
        config.read(text);
        return config;
    }

    /** Sections of the file. */
    private enum Section { NONE, INSTALL, OVERWRITE, KEEP_EXISTING, VERSION }

    private void read(String text) {
        Section section = Section.NONE;
        Set<String> seen = new HashSet<>();
        String[] lines = text.split("\\r?\\n|\\r", -1);
        for (int i = 0; i < lines.length; i++) {
            int lineNumber = i + 1;
            String line = lines[i].strip();
            if (i == 0 && line.startsWith("﻿")) line = line.substring(1).strip();
            if (line.isEmpty() || line.startsWith(";")) continue;
            if (line.startsWith("[") && line.endsWith("]")) {
                String name = line.substring(1, line.length() - 1).strip().toLowerCase(Locale.ROOT);
                section = switch (name) {
                    case "install" -> Section.INSTALL;
                    case "overwrite" -> Section.OVERWRITE;
                    case "keepexisting" -> Section.KEEP_EXISTING;
                    case "version" -> Section.VERSION;
                    default -> null;
                };
                if (section == null) {
                    errors.add(AhkBundle.message("install.ini.section.unknown", lineNumber, line));
                    section = Section.NONE;
                    seen.clear();
                    // the lines of an unknown section are skipped without further errors
                    while (i + 1 < lines.length && !lines[i + 1].strip().startsWith("[")) i++;
                    continue;
                }
                seen.clear();
                continue;
            }
            int eq = line.indexOf('=');
            if (section == Section.NONE) {
                errors.add(AhkBundle.message("install.ini.outside.section", lineNumber));
                continue;
            }
            if (eq <= 0) {
                errors.add(AhkBundle.message("install.ini.no.equals", lineNumber, line));
                continue;
            }
            String key = line.substring(0, eq).strip();
            String value = unquote(line.substring(eq + 1).strip());
            String normalized = key.replace('/', '\\').toLowerCase(Locale.ROOT);
            if (!seen.add(normalized)) {
                errors.add(AhkBundle.message("install.ini.duplicate", lineNumber, key));
                continue;
            }
            if (section == Section.INSTALL) {
                setInstallKey(key, value, lineNumber);
            } else if (section == Section.VERSION) {
                setVersionKey(key, value, lineNumber);
            } else if (value.isEmpty()) {
                errors.add(AhkBundle.message("install.ini.target.empty", lineNumber, key));
            } else {
                entries.add(new Entry(key, value, section == Section.KEEP_EXISTING, lineNumber));
            }
        }
    }

    /** Removes one pair of enclosing double quotes. */
    static @NotNull String unquote(@NotNull String value) {
        return value.length() >= 2 && value.startsWith("\"") && value.endsWith("\"")
                ? value.substring(1, value.length() - 1) : value;
    }

    private void setInstallKey(String key, String value, int line) {
        switch (key.toLowerCase(Locale.ROOT)) {
            case "destination" -> destination = value.isEmpty() ? null : value;
            case "message" -> message = value.isEmpty() ? null : value;
            case "requiresadmin" -> requiresAdmin = bool(key, value, line, false);
            case "generateoverview" -> generateOverview = bool(key, value, line, true);
            case "compilebeforeinstall" -> compile = bool(key, value, line, false);
            default -> errors.add(AhkBundle.message("install.ini.key.unknown", line, key));
        }
    }

    private void setVersionKey(String key, String value, int line) {
        if (key.equalsIgnoreCase("TagPrefix")) {
            tagPrefix = value.isEmpty() ? null : value;
        } else {
            errors.add(AhkBundle.message("install.ini.key.unknown", line, key));
        }
    }

    /** {@code true}/{@code false} (any case); empty = the default; anything else is an error. */
    private boolean bool(String key, String value, int line, boolean defaultValue) {
        if (value.isEmpty()) return defaultValue;
        if (value.equalsIgnoreCase("true")) return true;
        if (value.equalsIgnoreCase("false")) return false;
        errors.add(AhkBundle.message("install.ini.bool", line, key, value));
        return defaultValue;
    }
}
