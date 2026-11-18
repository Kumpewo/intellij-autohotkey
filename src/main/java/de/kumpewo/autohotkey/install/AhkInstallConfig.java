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
 * Contents of the {@code settings.ahk-plugin.ini} in the project root (one tool = one project), read by
 * {@link #parse}. It holds the settings of the installer ({@link AhkInstaller}):
 * <ul>
 *   <li>{@code [Install]}: {@code Destination}, {@code Message}, {@code RequiresAdmin}, {@code GenerateOverview}
 *       and {@code CompileBeforeInstall} (both default true); the other switches default to false.</li>
 *   <li>{@code [Overwrite]} and {@code [KeepExisting]}: lines {@code source=target}; the source is relative to the
 *       project root (wildcards {@code *} and {@code ?} in the file name), the target absolute or relative to
 *       {@code Destination}. {@code [KeepExisting]} only copies if the target does not exist yet.</li>
 *   <li>{@code [SetVersion]}: lines {@code path=exclude} for "Set Version..." ({@link AhkSetVersion}); the path is
 *       relative to the project root and names files like a source of {@code [Overwrite]} (wildcards in the last
 *       name), or, ending with {@code \}, a folder with everything below it. Matching scripts keep their version.
 *       </li>
 * </ul>
 * Comments are lines starting with {@code ;} (leading whitespace allowed); a {@code ;} later in a line is text.
 * Keys and values are trimmed, and one pair of enclosing double quotes is removed (like Windows INI files);
 * spaces inside a key (a file name) are kept. Section and key
 * names are case-insensitive. Mistakes of the file itself (unknown section, line without {@code =}, a key or source
 * twice in a section) stop every action; mistakes of a section only stop the action that uses it: {@link #errors()}
 * for the installer (unknown key, a switch that is neither true nor false, missing target), {@link #versionErrors()}
 * for "Set Version..." (invalid path, value other than {@code exclude}). Resolving paths and checking files is the
 * job of {@link AhkInstallPlan} and {@link AhkInstaller}.
 */
public final class AhkInstallConfig {

    /** File name of the configuration in the project root. */
    public static final String FILE_NAME = "settings.ahk-plugin.ini";

    /**
     * One line {@code source=target} of {@code [Overwrite]} or {@code [KeepExisting]}.
     *
     * @param source       source as written (relative to the project root)
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
    private boolean compile = true;
    private final List<Entry> entries = new ArrayList<>();
    private final List<String> exclusions = new ArrayList<>();
    private final List<Problem> problems = new ArrayList<>();

    /** Which action a mistake stops: {@code FILE} = every action (a mistake of the file itself). */
    private enum Scope { FILE, INSTALL, VERSION }

    /** A mistake in the file, in file order. */
    private record Problem(@NotNull Scope scope, @NotNull String text) {
    }

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

    /**
     * {@code CompileBeforeInstall} (default true): first compile the executables of the copy lines (each source that
     * names an {@code .exe} without wildcards and has a script of the same name next to it).
     */
    public boolean compile() {
        return compile;
    }

    /** The copy lines in file order. */
    public @NotNull List<Entry> entries() {
        return entries;
    }

    /** The paths of {@code [SetVersion]} with the value {@code exclude}, with {@code \} as separator. */
    public @NotNull List<String> exclusions() {
        return exclusions;
    }

    /** Mistakes that stop the installer (of the file itself and of its sections) in file order; empty if valid. */
    public @NotNull List<String> errors() {
        return problems(Scope.INSTALL);
    }

    /** Mistakes that stop "Set Version..." (of the file itself and of {@code [SetVersion]}) in file order. */
    public @NotNull List<String> versionErrors() {
        return problems(Scope.VERSION);
    }

    /** The mistakes of the file itself and of the given scope, in file order. */
    private @NotNull List<String> problems(@NotNull Scope scope) {
        List<String> result = new ArrayList<>();
        for (Problem problem : problems) {
            if (problem.scope() == Scope.FILE || problem.scope() == scope) result.add(problem.text());
        }
        return result;
    }

    /**
     * Is the file excluded from "Set Version..." by a line of {@code [SetVersion]}? Names are compared
     * case-insensitively.
     *
     * @param relative path of the file relative to the project root, separator {@code \} or {@code /}
     */
    public boolean excludes(@NotNull String relative) {
        String[] segments = relative.replace('/', '\\').split("\\\\");
        for (String exclusion : exclusions) {
            boolean folder = exclusion.endsWith("\\");
            String[] pattern = (folder ? exclusion.substring(0, exclusion.length() - 1) : exclusion).split("\\\\");
            // a file pattern names the file itself, a folder pattern any file below the folder
            if (folder ? segments.length <= pattern.length : segments.length != pattern.length) continue;
            boolean match = true;
            for (int i = 0; i < pattern.length && match; i++) {
                match = i == pattern.length - 1
                        ? AhkInstallPlan.wildcard(pattern[i]).matcher(segments[i]).matches()
                        : pattern[i].equalsIgnoreCase(segments[i]);
            }
            if (match) return true;
        }
        return false;
    }

    /** Reads the text of a {@code settings.ahk-plugin.ini}. */
    public static @NotNull AhkInstallConfig parse(@NotNull String text) {
        AhkInstallConfig config = new AhkInstallConfig();
        config.read(text);
        return config;
    }

    /** Sections of the file. */
    private enum Section { NONE, INSTALL, OVERWRITE, KEEP_EXISTING, SET_VERSION }

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
                    case "setversion" -> Section.SET_VERSION;
                    default -> null;
                };
                if (section == null) {
                    problem(Scope.FILE, AhkBundle.message("install.ini.section.unknown", lineNumber, line));
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
                problem(Scope.FILE, AhkBundle.message("install.ini.outside.section", lineNumber));
                continue;
            }
            if (eq <= 0) {
                problem(Scope.FILE, AhkBundle.message("install.ini.no.equals", lineNumber, line));
                continue;
            }
            String key = unquote(line.substring(0, eq).strip());
            String value = unquote(line.substring(eq + 1).strip());
            String normalized = key.replace('/', '\\').toLowerCase(Locale.ROOT);
            if (!seen.add(normalized)) {
                problem(Scope.FILE, AhkBundle.message("install.ini.duplicate", lineNumber, key));
                continue;
            }
            if (section == Section.INSTALL) {
                setInstallKey(key, value, lineNumber);
            } else if (section == Section.SET_VERSION) {
                addExclusion(key, value, lineNumber);
            } else if (value.isEmpty()) {
                problem(Scope.INSTALL, AhkBundle.message("install.ini.target.empty", lineNumber, key));
            } else {
                entries.add(new Entry(key, value, section == Section.KEEP_EXISTING, lineNumber));
            }
        }
    }

    /**
     * Checks a line of {@code [SetVersion]}: the value must be {@code exclude} (any case); the path must be relative
     * to the project root, without {@code ..}, drive or wildcards in folder names, and must not be the root itself.
     */
    private void addExclusion(String key, String value, int line) {
        if (!value.equalsIgnoreCase("exclude")) {
            problem(Scope.VERSION, AhkBundle.message("install.ini.exclude.value", line, key, value));
            return;
        }
        String path = key.replace('/', '\\');
        String inner = path.endsWith("\\") ? path.substring(0, path.length() - 1) : path;
        int slash = inner.lastIndexOf('\\');
        String folderPart = slash < 0 ? "" : inner.substring(0, slash);
        boolean invalid = inner.isEmpty() || inner.startsWith("\\") || inner.contains(":") || inner.contains("\\\\")
                || folderPart.contains("*") || folderPart.contains("?");
        for (String segment : inner.split("\\\\")) {
            if (segment.equals("..") || segment.equals(".")) invalid = true;
        }
        if (invalid) {
            problem(Scope.VERSION, AhkBundle.message("install.ini.exclude.invalid", line, key));
        } else {
            exclusions.add(path);
        }
    }

    /** Records a mistake. */
    private void problem(@NotNull Scope scope, @NotNull String text) {
        problems.add(new Problem(scope, text));
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
            case "compilebeforeinstall" -> compile = bool(key, value, line, true);
            default -> problem(Scope.INSTALL, AhkBundle.message("install.ini.key.unknown", line, key));
        }
    }

    /** {@code true}/{@code false} (any case); empty = the default; anything else is an error. */
    private boolean bool(String key, String value, int line, boolean defaultValue) {
        if (value.isEmpty()) return defaultValue;
        if (value.equalsIgnoreCase("true")) return true;
        if (value.equalsIgnoreCase("false")) return false;
        problem(Scope.INSTALL, AhkBundle.message("install.ini.bool", line, key, value));
        return defaultValue;
    }
}
