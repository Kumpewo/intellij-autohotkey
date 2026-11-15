package de.kumpewo.autohotkey.settings;

import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.components.PersistentStateComponent;
import com.intellij.openapi.components.Service;
import com.intellij.openapi.components.State;
import com.intellij.openapi.components.Storage;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * IDE-wide settings of the plugin (file {@code autohotkey.xml} in the configuration directory): path to the
 * AutoHotkey program, command line of the external help, when to validate with AutoHotkey, path to the compiler
 * Ahk2Exe and its compression. Empty paths = detect automatically ({@link #detectAutoHotkey()},
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
    }

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
    }

    /** Configured path (empty = automatic). */
    public @NotNull String getAutoHotkeyPath() {
        return data.autoHotkeyPath == null ? "" : data.autoHotkeyPath;
    }

    /** Sets the path (trimmed; null = empty = automatic). */
    public void setAutoHotkeyPath(@Nullable String path) {
        data.autoHotkeyPath = path == null ? "" : path.trim();
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
     * Program for running, validating or debugging {@code script}: like {@link #findAutoHotkey()}, but if that is the
     * launcher and the script requires AutoHotkey 2.0 with {@code #Requires} (not 2.1 or later), the v2 interpreter
     * next to the launcher ({@link #v2Interpreter}); the launcher would only choose it after starting itself.
     */
    public @Nullable String findAutoHotkey(@Nullable String script) {
        String exe = findAutoHotkey();
        if (exe == null || script == null || script.isEmpty()) return exe;
        try {
            Path exePath = Path.of(exe);
            if (launcherScript(exePath) == null) return exe;
            String text = de.kumpewo.autohotkey.compile.AhkCompiler.readDirectives(Path.of(script));
            de.kumpewo.autohotkey.parser.AhkVersion.Requirement requirement =
                    text == null ? null : de.kumpewo.autohotkey.parser.AhkVersion.scriptRequirement(text);
            if (requirement == null || !requirement.v20()) return exe;
            Path interpreter = v2Interpreter(exePath, requirement.bits());
            return interpreter == null ? exe : interpreter.toString();
        } catch (java.nio.file.InvalidPathException e) {
            return exe;
        }
    }

    /**
     * Interpreter for debugging {@code script}: never the launcher (it would not pass {@code /Debug} on). The
     * configured or detected program if it is an interpreter, otherwise the v2 interpreter next to the launcher,
     * with the bitness from {@code #Requires} if given; {@code null} if none is found.
     */
    public @Nullable String findInterpreter(@Nullable String script) {
        String exe = findAutoHotkey();
        if (exe == null) return null;
        try {
            Path exePath = Path.of(exe);
            if (launcherScript(exePath) == null) return exe;
            int bits = 0;
            if (script != null && !script.isEmpty()) {
                String text = de.kumpewo.autohotkey.compile.AhkCompiler.readDirectives(Path.of(script));
                de.kumpewo.autohotkey.parser.AhkVersion.Requirement requirement =
                        text == null ? null : de.kumpewo.autohotkey.parser.AhkVersion.scriptRequirement(text);
                if (requirement != null) bits = requirement.bits();
            }
            Path interpreter = v2Interpreter(exePath, bits);
            return interpreter == null ? null : interpreter.toString();
        } catch (java.nio.file.InvalidPathException e) {
            return null;
        }
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
        Path dir = root.resolve("v2");
        List<String> names = bits == 32 ? List.of("AutoHotkey32.exe")
                : bits == 64 ? List.of("AutoHotkey64.exe")
                : List.of("AutoHotkey.exe", "AutoHotkey64.exe", "AutoHotkey32.exe");
        for (String name : names) {
            Path candidate = dir.resolve(name);
            if (Files.isRegularFile(candidate)) return candidate;
        }
        return null;
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
