package de.kumpewo.autohotkey.run;

import de.kumpewo.autohotkey.AhkBundle;
import com.intellij.execution.ExecutionException;
import com.intellij.execution.Executor;
import com.intellij.execution.configurations.ConfigurationFactory;
import com.intellij.execution.configurations.LocatableConfigurationBase;
import com.intellij.execution.configurations.RunConfiguration;
import com.intellij.execution.configurations.RunProfileState;
import com.intellij.execution.configurations.RuntimeConfigurationError;
import com.intellij.execution.configurations.RuntimeConfigurationException;
import com.intellij.execution.runners.ExecutionEnvironment;
import com.intellij.openapi.options.SettingsEditor;
import com.intellij.openapi.options.ShowSettingsUtil;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.util.io.FileUtil;
import de.kumpewo.autohotkey.compile.AhkCompiler;
import de.kumpewo.autohotkey.settings.AhkSettings;
import de.kumpewo.autohotkey.settings.AhkSettingsConfigurable;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;

/**
 * Run configuration "AutoHotkey": a script (the current file or e.g. the main script) with parameters and a
 * working directory. The program comes from the settings ({@link AhkSettings}). Optionally the script is compiled
 * with Ahk2Exe first and the executable runs instead ({@link #isCompileFirst()}, {@link ExeConsole}).
 */
public final class AhkRunConfiguration extends LocatableConfigurationBase<AhkRunConfigurationOptions> {

    /** Where the compiled executable runs. */
    public enum ExeConsole {
        /** In a terminal (pseudo console) in the Run tool window: ANSI escape sequences, cursor control, input. */
        TERMINAL,
        /** In a separate console window (PowerShell {@code Start-Process -Wait}). */
        WINDOW
    }

    AhkRunConfiguration(@NotNull Project project, @NotNull ConfigurationFactory factory, @Nullable String name) {
        super(project, factory, name);
    }

    /** The stored values. */
    @Override
    protected @NotNull AhkRunConfigurationOptions getOptions() {
        return (AhkRunConfigurationOptions) super.getOptions();
    }

    /** Path of the script (system-independent). */
    public @NotNull String getScriptPath() {
        return nonNull(getOptions().getScriptPath());
    }

    /** Sets the path of the script (stored system-independent). */
    public void setScriptPath(@Nullable String path) {
        getOptions().setScriptPath(path == null ? "" : FileUtil.toSystemIndependentName(path.trim()));
    }

    /** Parameters passed to the script. */
    public @NotNull String getScriptParameters() {
        return nonNull(getOptions().getScriptParameters());
    }

    /** Sets the parameters passed to the script. */
    public void setScriptParameters(@Nullable String parameters) {
        getOptions().setScriptParameters(parameters == null ? "" : parameters.trim());
    }

    /** Configured working directory, empty = directory of the script. */
    public @NotNull String getWorkingDirectory() {
        return nonNull(getOptions().getWorkingDirectory());
    }

    /** Sets the working directory (stored system-independent). */
    public void setWorkingDirectory(@Nullable String directory) {
        getOptions().setWorkingDirectory(directory == null ? "" : FileUtil.toSystemIndependentName(directory.trim()));
    }

    /** True = compile with Ahk2Exe first and run the executable with the parameters instead of the script. */
    public boolean isCompileFirst() {
        return getOptions().isCompileFirst();
    }

    /** Sets whether to compile first. */
    public void setCompileFirst(boolean compileFirst) {
        getOptions().setCompileFirst(compileFirst);
    }

    /** Where the compiled executable runs; unknown or missing value = {@link ExeConsole#TERMINAL}. */
    public @NotNull ExeConsole getExeConsole() {
        for (ExeConsole console : ExeConsole.values()) {
            if (console.name().equals(getOptions().getExeConsole())) return console;
        }
        return ExeConsole.TERMINAL;
    }

    /** Sets where the compiled executable runs. */
    public void setExeConsole(@NotNull ExeConsole console) {
        getOptions().setExeConsole(console.name());
    }

    /** Working directory for the start: the configured one or the directory of the script. */
    public @NotNull String getEffectiveWorkingDirectory() {
        String directory = getWorkingDirectory();
        if (!directory.isEmpty()) return directory;
        String parent = new File(getScriptPath()).getParent();
        return parent == null ? "" : FileUtil.toSystemIndependentName(parent);
    }

    /** Suggested name: the file name of the script. */
    @Override
    public @Nullable String suggestedName() {
        String script = getScriptPath();
        return script.isEmpty() ? null : new File(script).getName();
    }

    /** The form of the configuration. */
    @Override
    public @NotNull SettingsEditor<? extends RunConfiguration> getConfigurationEditor() {
        return new AhkRunConfigurationEditor(getProject());
    }

    /**
     * Checks script, working directory and AutoHotkey program, with "compile first" Ahk2Exe and the line
     * {@code ;@Ahk2Exe-Base} in the script instead of the program; errors are shown in the run dialog.
     */
    @Override
    public void checkConfiguration() throws RuntimeConfigurationException {
        String script = getScriptPath();
        if (script.isEmpty()) throw new RuntimeConfigurationError(AhkBundle.message("run.no.script"));
        if (!isFile(script)) throw new RuntimeConfigurationError(AhkBundle.message("run.script.not.found",
                FileUtil.toSystemDependentName(script)));
        String directory = getWorkingDirectory();
        if (!directory.isEmpty() && !isDirectory(directory)) {
            throw new RuntimeConfigurationError(AhkBundle.message("run.directory.not.found",
                    FileUtil.toSystemDependentName(directory)));
        }
        if (isCompileFirst()) {
            if (!AhkCompiler.hasBaseDirective(Path.of(script))) {
                throw new RuntimeConfigurationError(AhkBundle.message("run.compile.no.base"));
            }
            String ahk2Exe = AhkSettings.getInstance().findAhk2Exe();
            if (ahk2Exe == null || !isFile(ahk2Exe)) {
                throw new RuntimeConfigurationError(AhkBundle.message("compile.ahk2exe.not.found"), this::openSettings);
            }
            return;
        }
        String exe = AhkSettings.getInstance().findAutoHotkey(script);
        if (exe == null) {
            throw new RuntimeConfigurationError(AhkSettings.getInstance().notFoundMessage(script), this::openSettings);
        }
        if (!isFile(exe)) {
            throw new RuntimeConfigurationError(AhkBundle.message("run.exe.not.found", String.valueOf(exe)), this::openSettings);
        }
    }

    /** Quick fix: opens the AutoHotkey settings page. */
    private void openSettings() {
        ShowSettingsUtil.getInstance().showSettingsDialog(getProject(), AhkSettingsConfigurable.class);
    }

    /** Starts the script ({@link AhkRunState}). */
    @Override
    public @Nullable RunProfileState getState(@NotNull Executor executor, @NotNull ExecutionEnvironment environment)
            throws ExecutionException {
        return new AhkRunState(environment, this);
    }

    /** Existing regular file. */
    private static boolean isFile(String path) {
        try {
            return Files.isRegularFile(Path.of(path));
        } catch (InvalidPathException e) {
            return false;
        }
    }

    /** Existing directory. */
    private static boolean isDirectory(String path) {
        try {
            return Files.isDirectory(Path.of(path));
        } catch (InvalidPathException e) {
            return false;
        }
    }

    /** Empty string instead of null. */
    private static String nonNull(String value) {
        return value == null ? "" : value;
    }
}
