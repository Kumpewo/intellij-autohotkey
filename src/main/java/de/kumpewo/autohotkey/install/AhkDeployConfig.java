package de.kumpewo.autohotkey.install;

import de.kumpewo.autohotkey.AhkBundle;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Contents of the {@code settings.ahk-v2-plugin.ini} in the project root (one tool = one project), read by
 * {@link #parse}. It holds the settings of Deploy ({@link AhkDeployer}):
 * <ul>
 *   <li>{@code [Deploy]}: {@code Destination}, {@code Message}, {@code Banner}, {@code RequiresAdmin},
 *       {@code GenerateOverview}, {@code Compile} and {@code EmbedOrigin} (these three default true);
 *       {@code RequiresAdmin} defaults to false.</li>
 *   <li>{@code [Install]} and {@code [InstallIfMissing]}: lines {@code source=target}; the source is relative to the
 *       project root (wildcards {@code *} and {@code ?} in the file name), the target absolute or relative to
 *       {@code Destination}. {@code [InstallIfMissing]} only copies if the target does not exist yet. The target
 *       {@value #TARGET_PLACEHOLDER} is an error (still to be written by hand).</li>
 *   <li>{@code [Cleanup]}: lines {@code selector=folder}; the folder (absolute or relative to {@code Destination}) is
 *       where the selector applies, the selector names files relative to it (wildcards in the file name). The same
 *       selector may appear in several lines.</li>
 *   <li>{@code [AddBanner]} and {@code [SetVersion]}: lines {@code selector=value} ({@link AhkFileRules}):
 *       the selector is written like a line of {@code .gitignore} ({@link AhkPathPattern}), the value includes or
 *       excludes the files and may name the comment style. Whether {@code [AddBanner]} has an effect is
 *       checked by {@link AhkDeployPlan}.</li>
 * </ul>
 * Comments are lines starting with {@code ;} (leading whitespace allowed); a {@code ;} later in a line is text.
 * Keys and values are trimmed, and one pair of enclosing double quotes is removed (like Windows INI files);
 * spaces inside a key (a file name) are kept. Section and key
 * names are case-insensitive. Mistakes of the file itself (unknown section, line without {@code =}, a key or source
 * twice in a section) stop every action; mistakes of a section only stop the action that uses it: {@link #errors()}
 * for Deploy (unknown key, a switch that is neither true nor false, missing target, invalid line of
 * {@code [AddBanner]}), {@link #versionErrors()} for "Set Version..." (invalid selector or value). Resolving paths
 * and checking files is the
 * job of {@link AhkDeployPlan} and {@link AhkDeployer}.
 */
public final class AhkDeployConfig {

    /** File name of the configuration in the project root. */
    public static final String FILE_NAME = "settings.ahk-v2-plugin.ini";

    /**
     * File name of the configuration of plugin version 2.1, with the old names ({@link AhkSettingsFormat}): still read
     * if there is no {@link #FILE_NAME}; "Update Settings" turns it into one.
     */
    public static final String LEGACY_FILE_NAME = "settings.ahk-plugin.ini";

    /**
     * The settings file of a project root: {@link #FILE_NAME} if it exists, otherwise {@link #LEGACY_FILE_NAME} if
     * that exists, otherwise {@code null}.
     */
    public static @Nullable Path file(@NotNull Path root) {
        Path current = root.resolve(FILE_NAME);
        if (Files.isRegularFile(current)) return current;
        Path legacy = root.resolve(LEGACY_FILE_NAME);
        return Files.isRegularFile(legacy) ? legacy : null;
    }

    /** Is the file a settings file of version 2.1 (by its name)? */
    public static boolean isLegacy(@NotNull Path file) {
        return file.getFileName().toString().equalsIgnoreCase(LEGACY_FILE_NAME);
    }

    /** Is the name one of the two names of a settings file? */
    public static boolean isSettingsName(@NotNull String name) {
        return name.equalsIgnoreCase(FILE_NAME) || name.equalsIgnoreCase(LEGACY_FILE_NAME);
    }

    /**
     * Reads a settings file (UTF-16 with byte order mark, otherwise UTF-8); a file named {@link #LEGACY_FILE_NAME}
     * with the names of version 2.1.
     */
    public static @NotNull AhkDeployConfig load(@NotNull Path file) throws IOException {
        return parse(AhkDeployer.readText(file), isLegacy(file));
    }

    /**
     * Target of a copy line that still has to be written by hand ("Add to Library Project..." when the file name was
     * taken): an error until it is replaced.
     */
    public static final String TARGET_PLACEHOLDER = "{set target}";

    /**
     * One line {@code source=target} of {@code [Install]} or {@code [InstallIfMissing]}.
     *
     * @param source       source as written (relative to the project root)
     * @param target       target as written (variables not yet resolved)
     * @param ifMissing true for {@code [InstallIfMissing]}
     * @param line         1-based line in the file
     */
    public record Entry(@NotNull String source, @NotNull String target, boolean ifMissing, int line) {
    }

    /**
     * One line {@code selector=folder} of {@code [Cleanup]}: the files matching the selector (relative to the folder,
     * wildcards in the file name) are moved to the Recycle Bin before copying.
     *
     * @param selector the files as written
     * @param folder   the folder as written (variables not yet resolved; absolute or relative to {@code Destination})
     * @param line     1-based line in the file
     */
    public record CleanupEntry(@NotNull String selector, @NotNull String folder, int line) {
    }

    private @Nullable String destination;
    private @Nullable String message;
    private @Nullable String banner;
    private final List<AhkFileRules.Rule> bannerRules = new ArrayList<>();
    private final List<AhkFileRules.Rule> versionRules = new ArrayList<>();
    private boolean requiresAdmin;
    private boolean generateOverview = true;
    private boolean compile = true;
    private boolean embedOrigin = true;
    private final List<Entry> entries = new ArrayList<>();
    private final List<CleanupEntry> cleanup = new ArrayList<>();
    private final List<Problem> problems = new ArrayList<>();

    /** Which action a mistake stops: {@code FILE} = every action (a mistake of the file itself). */
    private enum Scope { FILE, DEPLOY, VERSION }

    /** A mistake in the file, in file order. */
    private record Problem(@NotNull Scope scope, @NotNull String text) {
    }

    private AhkDeployConfig() {
    }

    /** {@code Destination} as written, {@code null} if missing or empty. */
    public @Nullable String destination() {
        return destination;
    }

    /** {@code Message} as written, {@code null} if missing or empty. */
    public @Nullable String message() {
        return message;
    }

    /** {@code Banner} as written ({@code `n} not yet resolved), {@code null} if missing or empty. */
    public @Nullable String banner() {
        return banner;
    }

    /** The lines of {@code [AddBanner]} in file order. */
    public @NotNull List<AhkFileRules.Rule> bannerRules() {
        return bannerRules;
    }

    /** The lines of {@code [SetVersion]} in file order. */
    public @NotNull List<AhkFileRules.Rule> versionRules() {
        return versionRules;
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
     * {@code Compile} (default true): first compile the executables of the copy lines (each source that
     * names an {@code .exe} without wildcards and has a script of the same name next to it).
     */
    public boolean compile() {
        return compile;
    }

    /**
     * {@code EmbedOrigin} (default true): the copies tell the project folder and the computer, in the built-in banners
     * ({@link AhkBanner#builtin}) and at the end of executables ({@link AhkExeOrigin}).
     */
    public boolean embedOrigin() {
        return embedOrigin;
    }

    /** The copy lines in file order. */
    public @NotNull List<Entry> entries() {
        return entries;
    }

    /** The lines of {@code [Cleanup]} in file order. */
    public @NotNull List<CleanupEntry> cleanup() {
        return cleanup;
    }

    /** Mistakes that stop Deploy (of the file itself and of its sections) in file order; empty if valid. */
    public @NotNull List<String> errors() {
        return problems(Scope.DEPLOY);
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

    /** Reads the text of a {@code settings.ahk-v2-plugin.ini}. */
    public static @NotNull AhkDeployConfig parse(@NotNull String text) {
        return parse(text, false);
    }

    /**
     * Reads the text of a settings file; {@code legacy}: a {@code settings.ahk-plugin.ini} with the names of version
     * 2.1 (and only those).
     */
    public static @NotNull AhkDeployConfig parse(@NotNull String text, boolean legacy) {
        AhkDeployConfig config = new AhkDeployConfig();
        config.legacy = legacy;
        config.read(text);
        return config;
    }

    /** Sections of the file. */
    private enum Section { NONE, DEPLOY, INSTALL, INSTALL_IF_MISSING, CLEANUP, ADD_BANNER, SET_VERSION }

    /** Is this a {@code settings.ahk-plugin.ini} with the names of version 2.1 ({@link AhkSettingsFormat})? */
    private boolean legacy;

    /**
     * True for a {@code settings.ahk-plugin.ini} with the names of version 2.1 ({@code [Install]} for the options,
     * {@code [Overwrite]} …): it is read as before, and the confirmation offers to update it
     * ({@link AhkSettingsFormat#updated}).
     */
    public boolean legacy() {
        return legacy;
    }

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
                String written = line.substring(1, line.length() - 1).strip().toLowerCase(Locale.ROOT);
                String renamed = legacy ? null : AhkSettingsFormat.newSection(written);
                if (renamed != null) {
                    // an old name in the new file: say which name it has now
                    problem(Scope.FILE, AhkBundle.message("deploy.ini.section.old", lineNumber, line, renamed));
                    section = Section.NONE;
                    seen.clear();
                    while (i + 1 < lines.length && !lines[i + 1].strip().startsWith("[")) i++;
                    continue;
                }
                String name = AhkSettingsFormat.current(written, legacy);
                section = switch (name) {
                    case "deploy" -> Section.DEPLOY;
                    case "install" -> Section.INSTALL;
                    case "installifmissing" -> Section.INSTALL_IF_MISSING;
                    case "cleanup" -> Section.CLEANUP;
                    case "addbanner" -> Section.ADD_BANNER;
                    case "setversion" -> Section.SET_VERSION;
                    default -> null;
                };
                if (section == null) {
                    problem(Scope.FILE, AhkBundle.message(legacy ? "deploy.ini.section.unknown.legacy"
                            : "deploy.ini.section.unknown", lineNumber, line));
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
                problem(Scope.FILE, AhkBundle.message("deploy.ini.outside.section", lineNumber));
                continue;
            }
            if (eq <= 0) {
                problem(Scope.FILE, AhkBundle.message("deploy.ini.no.equals", lineNumber, line));
                continue;
            }
            String key = unquote(line.substring(0, eq).strip());
            String value = unquote(line.substring(eq + 1).strip());
            String normalized = key.replace('/', '\\').toLowerCase(Locale.ROOT);
            // [Cleanup] may name the same files in several folders
            if (section != Section.CLEANUP && !seen.add(normalized)) {
                problem(Scope.FILE, AhkBundle.message("deploy.ini.duplicate", lineNumber, key));
                continue;
            }
            if (section == Section.CLEANUP) {
                if (value.isEmpty()) {
                    problem(Scope.DEPLOY, AhkBundle.message("deploy.ini.cleanup.folder.empty", lineNumber, key));
                } else {
                    cleanup.add(new CleanupEntry(key, value, lineNumber));
                }
            } else if (section == Section.DEPLOY) {
                setDeployKey(key, value, lineNumber);
            } else if (section == Section.SET_VERSION) {
                addRule(versionRules, Scope.VERSION, key, value, lineNumber);
            } else if (section == Section.ADD_BANNER) {
                addRule(bannerRules, Scope.DEPLOY, key, value, lineNumber);
            } else if (value.isEmpty()) {
                problem(Scope.DEPLOY, AhkBundle.message("deploy.ini.target.empty", lineNumber, key));
            } else if (!legacy && section == Section.INSTALL && AhkSettingsFormat.isOldOption(key)) {
                // [Install] of version 2.1 held the options
                problem(Scope.DEPLOY, AhkBundle.message("deploy.ini.install.option", lineNumber, key));
            } else if (value.equalsIgnoreCase(TARGET_PLACEHOLDER)) {
                problem(Scope.DEPLOY, AhkBundle.message("deploy.ini.target.placeholder", lineNumber, key,
                        TARGET_PLACEHOLDER));
            } else {
                entries.add(new Entry(key, value, section == Section.INSTALL_IF_MISSING, lineNumber));
            }
        }
    }

    /**
     * A line {@code selector=value} of {@code [AddBanner]} or {@code [SetVersion]}: the selector must be a valid
     * {@link AhkPathPattern}, the value one of the forms of {@link AhkFileRules}.
     */
    private void addRule(List<AhkFileRules.Rule> rules, Scope scope, String key, String value, int line) {
        AhkPathPattern pattern = AhkPathPattern.compile(key);
        if (pattern == null) {
            problem(scope, AhkBundle.message("deploy.ini.selector.invalid", line, key));
            return;
        }
        boolean[] include = new boolean[1];
        AhkFileRules.Style style = AhkFileRules.parseValue(value, include);
        if (style == AhkFileRules.INVALID) {
            problem(scope, AhkBundle.message("deploy.ini.rule.value", line, key, value));
            return;
        }
        rules.add(new AhkFileRules.Rule(pattern, include[0], style, value, line));
    }

    /** Records a mistake; in a file of version 2.1 a message that starts with the file name names that file. */
    private void problem(@NotNull Scope scope, @NotNull String text) {
        if (legacy && text.startsWith(FILE_NAME)) text = LEGACY_FILE_NAME + text.substring(FILE_NAME.length());
        problems.add(new Problem(scope, text));
    }

    /** Removes one pair of enclosing double quotes. */
    static @NotNull String unquote(@NotNull String value) {
        return value.length() >= 2 && value.startsWith("\"") && value.endsWith("\"")
                ? value.substring(1, value.length() - 1) : value;
    }

    /** A key of {@code [Deploy]}; in a file of version 2.1 its old names, too ({@link AhkSettingsFormat#currentKey}). */
    private void setDeployKey(String key, String value, int line) {
        String name = key.toLowerCase(Locale.ROOT);
        if (legacy) {
            // the old file knows only the old names
            if (name.equals("banner") || name.equals("compile")) {
                problem(Scope.DEPLOY, AhkBundle.message("deploy.ini.key.unknown", line, key));
                return;
            }
            name = AhkSettingsFormat.currentKey(name);
        }
        switch (name) {
            case "destination" -> destination = value.isEmpty() ? null : value;
            case "message" -> message = value.isEmpty() ? null : value;
            case "banner" -> banner = value.isEmpty() ? null : value;
            case "requiresadmin" -> requiresAdmin = bool(key, value, line, false);
            case "generateoverview" -> generateOverview = bool(key, value, line, true);
            case "compile" -> compile = bool(key, value, line, true);
            case "embedorigin" -> embedOrigin = bool(key, value, line, true);
            default -> {
                String renamed = AhkSettingsFormat.currentKey(name);
                problem(Scope.DEPLOY, renamed.equals(name)
                        ? AhkBundle.message("deploy.ini.key.unknown", line, key)
                        : AhkBundle.message("deploy.ini.key.old", line, key, AhkSettingsFormat.keyName(renamed)));
            }
        }
    }

    /**
     * {@code yes}/{@code no} (any case); {@code true}/{@code false} are still read for files of older versions but
     * not documented; empty = the default; anything else is an error.
     */
    private boolean bool(String key, String value, int line, boolean defaultValue) {
        if (value.isEmpty()) return defaultValue;
        if (value.equalsIgnoreCase("yes") || value.equalsIgnoreCase("true")) return true;
        if (value.equalsIgnoreCase("no") || value.equalsIgnoreCase("false")) return false;
        problem(Scope.DEPLOY, AhkBundle.message("deploy.ini.bool", line, key, value));
        return defaultValue;
    }
}
