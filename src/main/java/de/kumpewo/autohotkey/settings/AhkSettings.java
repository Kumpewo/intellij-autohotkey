package de.kumpewo.autohotkey.settings;

import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.components.PersistentStateComponent;
import com.intellij.openapi.components.Service;
import com.intellij.openapi.components.State;
import com.intellij.openapi.components.Storage;
import com.intellij.openapi.util.SimpleModificationTracker;
import de.kumpewo.autohotkey.parser.AhkLanguageLevel;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * IDE-wide settings of the plugin (file {@code autohotkey.xml} in the configuration directory): path to the
 * AutoHotkey program, command line of the external help, when to validate with AutoHotkey, path to the compiler
 * Ahk2Exe and its compression, language level of files without {@code #Requires} and whether the Project view
 * shows the language level. Empty paths = detect automatically ({@link #detectAutoHotkey()},
 * {@link #detectAhk2Exe()}). Used by the run configuration, the external help, the validation, the compilation and
 * the Lib folders of {@code #Include}.
 * Light service ({@code @Service}), therefore not registered in {@code plugin.xml}.
 */
@Service(Service.Level.APP)
@State(name = "AhkSettings", storages = @Storage("autohotkey.xml"))
public final class AhkSettings implements PersistentStateComponent<AhkSettings.Data> {

    /** Stored state (public fields, so that the platform serializes them). */
    public static final class Data {
        public String autoHotkeyPath = "";
        /** Command line of the external help (Shift+F1), %s = key; empty = automatic. */
        public String externalHelpCommand = "";
        /** When to validate automatically with {@code /validate} (name of a {@link ValidateTrigger}). */
        public String validateTrigger = ValidateTrigger.MANUAL.name();
        /** Path to {@code Ahk2Exe.exe}; empty = automatic. */
        public String ahk2ExePath = "";
        /** Compression of the compiled executable (name of a {@link Compression}). */
        public String compression = Compression.NONE.name();
        /** Language level of files without {@code #Requires AutoHotkey ...} (name of an {@link AhkLanguageLevel}). */
        public String defaultLanguageLevel = AhkLanguageLevel.V2_0.name();
        /** Path to the AutoHotkey v2.1 interpreter for files of language level v2.1; empty = automatic. */
        public String autoHotkeyV21Path = "";
        /** Show the language level of AutoHotkey files in the Project view ({@code AhkLanguageLevelDecorator}). */
        public boolean showLevelInProjectView = true;
        /** Folder whose subfolders are library projects ({@code AhkLibraryProjects}); empty = none. */
        public String libraryProjectsFolder = "";
        /** Write explanations into new {@code settings.ahk-v2-plugin.ini} files. */
        public boolean explainNewSettings = true;
    }

    /**
     * Changes whenever the default language level changes; cached language levels of files depend on it
     * ({@link AhkLanguageLevel#of}).
     */
    public static final SimpleModificationTracker LANGUAGE_LEVEL_TRACKER = new SimpleModificationTracker();

    /** Compression of the executable created by Ahk2Exe (switch {@code /compress}). */
    public enum Compression {
        /** No compression ({@code /compress 0}). */
        NONE("0"),
        /** UPX ({@code /compress 2}, {@code upx.exe} must be in the folder of Ahk2Exe). */
        UPX("2");

        /** Value of the switch {@code /compress}. */
        public final String argument;

        Compression(String argument) {
            this.argument = argument;
        }
    }

    /** When a script is validated with {@code AutoHotkey /validate} in addition to the action (AhkValidator). */
    public enum ValidateTrigger {
        /** Only by the action "Validate with AutoHotkey". */
        MANUAL,
        /** Also after the action "Reformat Code" on an AutoHotkey file. */
        AFTER_REFORMAT,
        /** Also after an AutoHotkey file has been saved (also by the automatic saving of the IDE). */
        ON_SAVE
    }

    /** File name of the launcher of the standard installation; it needs {@link #LAUNCHER_SCRIPT} as first argument. */
    public static final String LAUNCHER_EXE = "AutoHotkeyUX.exe";
    public static final String LAUNCHER_SCRIPT = "launcher.ahk";

    private Data data = new Data();

    /** The application-wide instance. */
    public static AhkSettings getInstance() {
        return ApplicationManager.getApplication().getService(AhkSettings.class);
    }

    /** State to be saved. */
    @Override
    public @NotNull Data getState() {
        return data;
    }

    /** Loaded state. */
    @Override
    public void loadState(@NotNull Data state) {
        data = state;
        LANGUAGE_LEVEL_TRACKER.incModificationCount();
    }

    /** Configured path (empty = automatic). */
    public @NotNull String getAutoHotkeyPath() {
        return data.autoHotkeyPath == null ? "" : data.autoHotkeyPath;
    }

    /** Sets the path (trimmed; null = empty = automatic). */
    public void setAutoHotkeyPath(@Nullable String path) {
        data.autoHotkeyPath = path == null ? "" : path.trim();
    }

    /** Configured path to the AutoHotkey v2.1 interpreter (empty = automatic). */
    public @NotNull String getAutoHotkeyV21Path() {
        return data.autoHotkeyV21Path == null ? "" : data.autoHotkeyV21Path;
    }

    /** Sets the path to the v2.1 interpreter (trimmed; null = empty = automatic). */
    public void setAutoHotkeyV21Path(@Nullable String path) {
        data.autoHotkeyV21Path = path == null ? "" : path.trim();
    }

    /** Command line of the external help ({@code %s} = key), empty = automatic (AhkExternalHelp). */
    public @NotNull String getExternalHelpCommand() {
        return data.externalHelpCommand == null ? "" : data.externalHelpCommand;
    }

    /** Sets the command line of the external help (trimmed; null = empty = automatic). */
    public void setExternalHelpCommand(@Nullable String command) {
        data.externalHelpCommand = command == null ? "" : command.trim();
    }

    /** When to validate automatically; unknown or missing value = {@link ValidateTrigger#MANUAL}. */
    public @NotNull ValidateTrigger getValidateTrigger() {
        for (ValidateTrigger trigger : ValidateTrigger.values()) {
            if (trigger.name().equals(data.validateTrigger)) return trigger;
        }
        return ValidateTrigger.MANUAL;
    }

    /** Sets when to validate automatically. */
    public void setValidateTrigger(@NotNull ValidateTrigger trigger) {
        data.validateTrigger = trigger.name();
    }

    /** Configured path to Ahk2Exe (empty = automatic). */
    public @NotNull String getAhk2ExePath() {
        return data.ahk2ExePath == null ? "" : data.ahk2ExePath;
    }

    /** Sets the path to Ahk2Exe (trimmed; null = empty = automatic). */
    public void setAhk2ExePath(@Nullable String path) {
        data.ahk2ExePath = path == null ? "" : path.trim();
    }

    /** Compression of the compiled executable; unknown or missing value = {@link Compression#NONE}. */
    public @NotNull Compression getCompression() {
        for (Compression compression : Compression.values()) {
            if (compression.name().equals(data.compression)) return compression;
        }
        return Compression.NONE;
    }

    /** Sets the compression of the compiled executable. */
    public void setCompression(@NotNull Compression compression) {
        data.compression = compression.name();
    }

    /**
     * Language level of files without {@code #Requires AutoHotkey ...}; unknown or missing value =
     * {@link AhkLanguageLevel#V2_0}.
     */
    public @NotNull AhkLanguageLevel getDefaultLanguageLevel() {
        for (AhkLanguageLevel level : AhkLanguageLevel.selectable()) {
            if (level.name().equals(data.defaultLanguageLevel)) return level;
        }
        return AhkLanguageLevel.V2_0;
    }

    /** Sets the language level of files without {@code #Requires}; a change invalidates the cached levels. */
    public void setDefaultLanguageLevel(@NotNull AhkLanguageLevel level) {
        if (level.name().equals(data.defaultLanguageLevel)) return;
        data.defaultLanguageLevel = level.name();
        LANGUAGE_LEVEL_TRACKER.incModificationCount();
    }

    /** Is the language level shown next to AutoHotkey files in the Project view? */
    public boolean isShowLevelInProjectView() {
        return data.showLevelInProjectView;
    }

    /** Shows or hides the language level next to AutoHotkey files in the Project view. */
    public void setShowLevelInProjectView(boolean show) {
        data.showLevelInProjectView = show;
    }

    /** Do new {@code settings.ahk-v2-plugin.ini} files contain explanations ({@code AhkSettingsExplain})? */
    public boolean isExplainNewSettings() {
        return data.explainNewSettings;
    }

    /** Sets whether new {@code settings.ahk-v2-plugin.ini} files contain explanations. */
    public void setExplainNewSettings(boolean explain) {
        data.explainNewSettings = explain;
    }

    /** Folder of the library projects (empty = none). */
    public @NotNull String getLibraryProjectsFolder() {
        return data.libraryProjectsFolder == null ? "" : data.libraryProjectsFolder;
    }

    /** Sets the folder of the library projects (trimmed; null = empty = none). */
    public void setLibraryProjectsFolder(@Nullable String folder) {
        data.libraryProjectsFolder = folder == null ? "" : folder.trim();
    }

    /**
     * {@link #getDefaultLanguageLevel()} of the application-wide instance; {@link AhkLanguageLevel#V2_0} if there is
     * no application (e.g. in plain unit tests).
     */
    public static @NotNull AhkLanguageLevel defaultLevel() {
        if (ApplicationManager.getApplication() == null) return AhkLanguageLevel.V2_0;
        AhkSettings settings = getInstance();
        return settings == null ? AhkLanguageLevel.V2_0 : settings.getDefaultLanguageLevel();
    }

    /** Configured path to Ahk2Exe or, if empty, the automatically detected one; {@code null} if nothing is found. */
    public @Nullable String findAhk2Exe() {
        String path = getAhk2ExePath();
        if (!path.isEmpty()) return path;
        Path found = detectAhk2Exe();
        return found == null ? null : found.toString();
    }

    /**
     * Looks for {@code Compiler\Ahk2Exe.exe} of the standard installation: first in the folder of the configured
     * AutoHotkey program and its parent folders (for {@code UX\AutoHotkeyUX.exe} and {@code v2\AutoHotkey.exe} the
     * installation folder), then in the usual installation directories.
     */
    public static @Nullable Path detectAhk2Exe() {
        List<Path> dirs = new ArrayList<>();
        String configured = getInstance() == null ? "" : getInstance().getAutoHotkeyPath();
        if (!configured.isEmpty()) {
            try {
                for (Path dir = Path.of(configured).getParent(); dir != null; dir = dir.getParent()) dirs.add(dir);
            } catch (java.nio.file.InvalidPathException ignored) {
                // invalid configured path: only the installation directories
            }
        }
        dirs.addAll(installationDirectories());
        for (Path dir : dirs) {
            Path compiler = dir.resolve("Compiler").resolve(AHK2EXE);
            if (Files.isRegularFile(compiler)) return compiler;
        }
        return null;
    }

    /** File name of the compiler. */
    public static final String AHK2EXE = "Ahk2Exe.exe";

    /** The usual installation directories of AutoHotkey (for all users and for the current user only). */
    private static @NotNull List<Path> installationDirectories() {
        List<Path> dirs = new ArrayList<>();
        for (String variable : new String[]{"ProgramW6432", "ProgramFiles", "ProgramFiles(x86)"}) {
            String value = System.getenv(variable);
            if (value != null && !value.isBlank()) dirs.add(Path.of(value, "AutoHotkey"));
        }
        String localAppData = System.getenv("LOCALAPPDATA");
        if (localAppData != null && !localAppData.isBlank()) dirs.add(Path.of(localAppData, "Programs", "AutoHotkey"));
        return dirs;
    }

    /**
     * What the saved script asks for when choosing the program: its language level ({@code #Requires}, without one
     * the default level of the settings, as for the parser), whether a {@code #Requires} with a version decided it,
     * and the bitness from {@code #Requires} (0 = none).
     */
    public record ScriptTarget(@NotNull AhkLanguageLevel level, boolean fromRequires, int bits) {
    }

    /**
     * {@link ScriptTarget} of the saved script (directives read from the file); without script or readable file
     * the default level and no bitness.
     */
    public @NotNull ScriptTarget scriptTarget(@Nullable String script) {
        de.kumpewo.autohotkey.parser.AhkVersion.Requirement requirement = null;
        if (script != null && !script.isEmpty()) {
            try {
                String text = de.kumpewo.autohotkey.compile.AhkCompiler.readDirectives(Path.of(script));
                requirement = text == null ? null : de.kumpewo.autohotkey.parser.AhkVersion.scriptRequirement(text);
            } catch (java.nio.file.InvalidPathException ignored) {
                // no readable script: the default level
            }
        }
        if (requirement == null) return new ScriptTarget(getDefaultLanguageLevel(), false, 0);
        AhkLanguageLevel level = !requirement.v2() ? AhkLanguageLevel.OTHER
                : requirement.v20() ? AhkLanguageLevel.V2_0 : AhkLanguageLevel.V2_1;
        return new ScriptTarget(level, true, requirement.bits());
    }

    /**
     * Program for running or validating {@code script}. Language level v2.1: the v2.1 interpreter
     * ({@link #findAutoHotkeyV21(int)}, never the launcher, which does not know an unregistered alpha and would offer
     * a download); {@code null} if there is none. Otherwise like {@link #findAutoHotkey()}, but if that is the
     * launcher and the script requires AutoHotkey 2.0 with {@code #Requires}, the v2 interpreter next to the launcher
     * ({@link #v2Interpreter}); the launcher would only choose it after starting itself.
     */
    public @Nullable String findAutoHotkey(@Nullable String script) {
        ScriptTarget target = scriptTarget(script);
        if (target.level() == AhkLanguageLevel.V2_1) return findAutoHotkeyV21(target.bits());
        String exe = findAutoHotkey();
        if (exe == null || script == null || script.isEmpty()) return exe;
        try {
            Path exePath = Path.of(exe);
            if (launcherScript(exePath) == null) return exe;
            if (!target.fromRequires() || target.level() != AhkLanguageLevel.V2_0) return exe;
            Path interpreter = v2Interpreter(exePath, target.bits());
            return interpreter == null ? exe : interpreter.toString();
        } catch (java.nio.file.InvalidPathException e) {
            return exe;
        }
    }

    /**
     * Interpreter for debugging {@code script}: never the launcher (it would not pass {@code /Debug} on). Language
     * level v2.1: the v2.1 interpreter ({@link #findAutoHotkeyV21(int)}). Otherwise the configured or detected
     * program if it is an interpreter, else the v2 interpreter next to the launcher, with the bitness from
     * {@code #Requires} if given; {@code null} if none is found.
     */
    public @Nullable String findInterpreter(@Nullable String script) {
        ScriptTarget target = scriptTarget(script);
        if (target.level() == AhkLanguageLevel.V2_1) return findAutoHotkeyV21(target.bits());
        String exe = findAutoHotkey();
        if (exe == null) return null;
        try {
            Path exePath = Path.of(exe);
            if (launcherScript(exePath) == null) return exe;
            Path interpreter = v2Interpreter(exePath, target.bits());
            return interpreter == null ? null : interpreter.toString();
        } catch (java.nio.file.InvalidPathException e) {
            return null;
        }
    }

    /** Message for a missing program: the v2.1 interpreter for a script of level v2.1, otherwise AutoHotkey. */
    public @NotNull String notFoundMessage(@Nullable String script) {
        return scriptTarget(script).level() == AhkLanguageLevel.V2_1
                ? de.kumpewo.autohotkey.AhkBundle.message("run.autohotkey.v21.not.found")
                : de.kumpewo.autohotkey.AhkBundle.message("run.autohotkey.not.found");
    }

    /**
     * The v2.1 interpreter: the configured one ({@link #getAutoHotkeyV21Path()}), otherwise the newest one found
     * ({@link #detectAutoHotkeyV21Dir()}). With a bitness (32/64 from {@code #Requires}) {@code AutoHotkey32.exe} or
     * {@code AutoHotkey64.exe} in the same folder if it exists; {@code null} if nothing is found.
     */
    public @Nullable String findAutoHotkeyV21(int bits) {
        String configured = getAutoHotkeyV21Path();
        if (!configured.isEmpty()) {
            try {
                Path exe = Path.of(configured);
                Path sibling = bits == 0 || exe.getParent() == null ? null
                        : exe.resolveSibling("AutoHotkey" + bits + ".exe");
                return sibling != null && Files.isRegularFile(sibling) ? sibling.toString() : configured;
            } catch (java.nio.file.InvalidPathException e) {
                return configured;
            }
        }
        Path dir = detectAutoHotkeyV21Dir();
        Path exe = dir == null ? null : interpreterIn(dir, bits);
        return exe == null ? null : exe.toString();
    }

    /** {@link #findAutoHotkeyV21(int)} without bitness, found automatically (placeholder of the settings page). */
    public static @Nullable Path detectAutoHotkeyV21() {
        Path dir = detectAutoHotkeyV21Dir();
        return dir == null ? null : interpreterIn(dir, 0);
    }

    /**
     * Folder of the newest installed AutoHotkey of version 2.1 or later: subfolders {@code v2.1...} (folder name =
     * version, e.g. {@code v2.1-alpha.39}) of the installation folder of the configured launcher and of the usual
     * installation directories, and the interpreters listed in the launcher's {@code UX\installed-files.csv};
     * {@code null} if there is none.
     */
    public static @Nullable Path detectAutoHotkeyV21Dir() {
        List<Path> roots = new ArrayList<>();
        String configured = getInstance() == null ? "" : getInstance().getAutoHotkeyPath();
        if (!configured.isEmpty()) {
            try {
                Path exe = Path.of(configured);
                Path ux = launcherScript(exe) == null ? null : exe.getParent();
                if (ux != null && ux.getParent() != null) roots.add(ux.getParent());
            } catch (java.nio.file.InvalidPathException ignored) {
                // invalid configured path: only the installation directories
            }
        }
        roots.addAll(installationDirectories());
        Path best = null;
        String bestVersion = null;
        for (Path root : roots) {
            for (VersionDir candidate : versionDirs(root)) {
                if (interpreterIn(candidate.dir(), 0) == null) continue;
                if (!de.kumpewo.autohotkey.parser.AhkVersion.atLeastV21(candidate.version())) continue;
                Integer c = bestVersion == null ? Integer.valueOf(1)
                        : de.kumpewo.autohotkey.parser.AhkVersion.compareVersions(candidate.version(), bestVersion);
                if (c != null && c > 0) {
                    best = candidate.dir();
                    bestVersion = candidate.version();
                }
            }
        }
        return best;
    }

    /** A folder with interpreters of one AutoHotkey version. */
    private record VersionDir(@NotNull Path dir, @NotNull String version) {
    }

    /** Folder name of a version folder of the installation ({@code v2.1-alpha.39}, {@code v2.0.30}). */
    private static final java.util.regex.Pattern VERSION_DIR = java.util.regex.Pattern.compile("(?i)v(\\d+\\.\\d+.*)");

    /**
     * Version folders of an installation folder: its subfolders named like a version, and the folders of the
     * interpreters in {@code UX\installed-files.csv} (columns {@code Hash,Version,Path,Description}, path relative
     * to the installation folder or absolute).
     */
    private static @NotNull List<VersionDir> versionDirs(@NotNull Path root) {
        List<VersionDir> result = new ArrayList<>();
        if (!Files.isDirectory(root)) return result;
        try (java.util.stream.Stream<Path> children = Files.list(root)) {
            children.filter(Files::isDirectory).forEach(dir -> {
                java.util.regex.Matcher m = VERSION_DIR.matcher(dir.getFileName().toString());
                if (m.matches()) result.add(new VersionDir(dir, m.group(1)));
            });
        } catch (java.io.IOException | RuntimeException ignored) {
            // unreadable folder: no version folders
        }
        Path csv = root.resolve("UX").resolve("installed-files.csv");
        if (Files.isRegularFile(csv)) {
            try {
                for (String line : Files.readAllLines(csv, java.nio.charset.StandardCharsets.UTF_8)) {
                    List<String> fields = csvFields(line);
                    if (fields.size() < 3 || !fields.get(2).matches("(?i).*\\\\AutoHotkey(32|64)?\\.exe")) continue;
                    Path exe = root.resolve(fields.get(2));
                    if (exe.getParent() != null) result.add(new VersionDir(exe.getParent(), fields.get(1)));
                }
            } catch (java.io.IOException | RuntimeException ignored) {
                // unreadable list: only the folders
            }
        }
        return result;
    }

    /** Fields of a CSV line (quotes around a field, {@code ""} inside quotes = one quote). */
    public static @NotNull List<String> csvFields(@NotNull String line) {
        List<String> fields = new ArrayList<>();
        StringBuilder field = new StringBuilder();
        boolean quoted = false;
        for (int i = 0; i < line.length(); i++) {
            char c = line.charAt(i);
            if (quoted) {
                if (c == '"' && i + 1 < line.length() && line.charAt(i + 1) == '"') {
                    field.append('"');
                    i++;
                } else if (c == '"') {
                    quoted = false;
                } else {
                    field.append(c);
                }
            } else if (c == '"') {
                quoted = true;
            } else if (c == ',') {
                fields.add(field.toString());
                field.setLength(0);
            } else {
                field.append(c);
            }
        }
        fields.add(field.toString());
        return fields;
    }

    /**
     * Interpreter in a version folder: {@code AutoHotkey32.exe}/{@code AutoHotkey64.exe} for 32/64 bits, otherwise
     * {@code AutoHotkey.exe}, {@code AutoHotkey64.exe}, {@code AutoHotkey32.exe}; {@code null} if none exists.
     */
    static @Nullable Path interpreterIn(@NotNull Path dir, int bits) {
        List<String> names = bits == 32 ? List.of("AutoHotkey32.exe")
                : bits == 64 ? List.of("AutoHotkey64.exe")
                : List.of("AutoHotkey.exe", "AutoHotkey64.exe", "AutoHotkey32.exe");
        for (String name : names) {
            Path candidate = dir.resolve(name);
            if (Files.isRegularFile(candidate)) return candidate;
        }
        return null;
    }

    /**
     * The v2 interpreter of the installation of the launcher ({@code UX\..\v2}): {@code AutoHotkey32.exe} or
     * {@code AutoHotkey64.exe} for 32/64 bits, otherwise {@code AutoHotkey.exe} (a link to the default bitness) and
     * as fallback {@code AutoHotkey64.exe}, {@code AutoHotkey32.exe}; {@code null} if none exists.
     */
    public static @Nullable Path v2Interpreter(@NotNull Path launcher, int bits) {
        Path ux = launcher.getParent();
        Path root = ux == null ? null : ux.getParent();
        if (root == null) return null;
        return interpreterIn(root.resolve("v2"), bits);
    }

    /** Configured path or, if empty, the automatically detected one; {@code null} if nothing can be found. */
    public @Nullable String findAutoHotkey() {
        String path = getAutoHotkeyPath();
        if (!path.isEmpty()) return path;
        Path found = detectAutoHotkey();
        return found == null ? null : found.toString();
    }

    /**
     * Looks for AutoHotkey in the usual installation directories (for all users and for the current user only).
     * Prefers the launcher ({@code UX\AutoHotkeyUX.exe}), which chooses the version from {@code #Requires},
     * otherwise {@code v2\AutoHotkey.exe}.
     */
    public static @Nullable Path detectAutoHotkey() {
        List<Path> dirs = installationDirectories();
        for (Path dir : dirs) {
            Path launcher = dir.resolve("UX").resolve(LAUNCHER_EXE);
            if (Files.isRegularFile(launcher) && launcherScript(launcher) != null) return launcher;
        }
        for (Path dir : dirs) {
            for (Path exe : new Path[]{dir.resolve("v2").resolve("AutoHotkey.exe"), dir.resolve("AutoHotkey.exe")}) {
                if (Files.isRegularFile(exe)) return exe;
            }
        }
        return null;
    }

    /**
     * Script of the launcher if {@code exe} is the launcher {@code AutoHotkeyUX.exe} and {@code launcher.ahk} is next
     * to it (call according to the help: {@code AutoHotkeyUX.exe launcher.ahk [switches] script [parameters]}).
     */
    public static @Nullable Path launcherScript(@NotNull Path exe) {
        Path name = exe.getFileName();
        if (name == null || !name.toString().equalsIgnoreCase(LAUNCHER_EXE)) return null;
        Path script = exe.resolveSibling(LAUNCHER_SCRIPT);
        return Files.isRegularFile(script) ? script : null;
    }
}
