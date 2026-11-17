package de.kumpewo.autohotkey.run;

import com.intellij.execution.DefaultExecutionResult;
import com.intellij.execution.ExecutionException;
import com.intellij.execution.ExecutionResult;
import com.intellij.execution.Executor;
import com.intellij.execution.configurations.CommandLineState;
import com.intellij.execution.configurations.PtyCommandLine;
import com.intellij.execution.process.KillableProcessHandler;
import com.intellij.execution.process.NopProcessHandler;
import com.intellij.execution.process.ProcessOutput;
import com.intellij.execution.runners.ProgramRunner;
import com.intellij.execution.ui.ConsoleView;
import com.intellij.execution.ui.ConsoleViewContentType;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.progress.EmptyProgressIndicator;
import com.intellij.openapi.progress.ProgressIndicator;
import com.intellij.openapi.progress.ProgressManager;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.util.ThrowableComputable;
import com.intellij.openapi.vfs.LocalFileSystem;
import com.intellij.terminal.TerminalExecutionConsole;
import de.kumpewo.autohotkey.AhkBundle;
import de.kumpewo.autohotkey.compile.AhkCompiler;
import com.intellij.execution.configurations.GeneralCommandLine;
import com.intellij.execution.filters.TextConsoleBuilder;
import com.intellij.execution.filters.TextConsoleBuilderFactory;
import com.intellij.execution.process.OSProcessHandler;
import com.intellij.execution.process.ProcessHandler;
import com.intellij.execution.process.ProcessHandlerFactory;
import com.intellij.execution.process.ProcessTerminatedListener;
import com.intellij.execution.runners.ExecutionEnvironment;
import com.intellij.openapi.util.io.FileUtil;
import de.kumpewo.autohotkey.settings.AhkSettings;
import org.jetbrains.annotations.NotNull;

import java.io.File;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.nio.file.Path;
import java.util.List;

/**
 * Starts the script: {@code AutoHotkey.exe /ErrorStdOut script parameters} (for the launcher
 * {@code AutoHotkeyUX.exe} with {@code launcher.ahk} before it; a script with {@code #Requires AutoHotkey v2.0} gets
 * the v2 interpreter directly, {@link AhkSettings#findAutoHotkey(String)}). AutoHotkey then writes load errors to
 * stderr instead of a dialog; {@link AhkErrorFilter} makes them clickable in the console.
 * <p>
 * Encoding: {@code /ErrorStdOut} without an encoding and {@code FileAppend …, "*"} both write in the ANSI code page
 * (CP0), so the console reads with the code page of the system ({@code native.encoding}).
 * <p>
 * With "compile first" ({@link AhkRunConfiguration#isCompileFirst()}) {@link #execute} runs Ahk2Exe
 * ({@link AhkCompiler}) and then the executable with the parameters, in a terminal in the Run tool window
 * (pseudo console, {@link TerminalExecutionConsole}) or in a separate console window (PowerShell
 * {@code Start-Process -Wait}).
 * The output of Ahk2Exe stands at the top of the console; if compiling fails, the executable is not started.
 */
public final class AhkRunState extends CommandLineState {

    private final AhkRunConfiguration configuration;

    AhkRunState(@NotNull ExecutionEnvironment environment, @NotNull AhkRunConfiguration configuration) {
        super(environment);
        this.configuration = configuration;
        TextConsoleBuilder builder = TextConsoleBuilderFactory.getInstance().createBuilder(environment.getProject());
        builder.addFilter(new AhkErrorFilter(environment.getProject()));
        setConsoleBuilder(builder);
    }

    /** Normal start ({@link #startProcess}) or, with "compile first", compile and run the executable. */
    @Override
    public @NotNull ExecutionResult execute(@NotNull Executor executor, @NotNull ProgramRunner<?> runner)
            throws ExecutionException {
        if (!configuration.isCompileFirst()) return super.execute(executor, runner);
        Project project = getEnvironment().getProject();
        String ahk2Exe = AhkSettings.getInstance().findAhk2Exe();
        if (ahk2Exe == null) throw new ExecutionException(AhkBundle.message("compile.ahk2exe.not.found"));
        String script = FileUtil.toSystemDependentName(configuration.getScriptPath());
        String name = new File(script).getName();
        AhkSettings.Compression compression = AhkSettings.getInstance().getCompression();
        ThrowableComputable<ProcessOutput, ExecutionException> compile = () -> {
            ProgressIndicator indicator = ProgressManager.getInstance().getProgressIndicator();
            return AhkCompiler.run(ahk2Exe, script, compression,
                    indicator != null ? indicator : new EmptyProgressIndicator());
        };
        ProcessOutput output = ApplicationManager.getApplication().isDispatchThread()
                ? ProgressManager.getInstance().runProcessWithProgressSynchronously(compile,
                        AhkBundle.message("compile.progress", name), true, project)
                : compile.compute();
        String exe = output.getExitCode() == 0 && !output.isTimeout() && !output.isCancelled()
                ? AhkCompiler.compiledFile(output.getStdout()) : null;
        String compileOutput = AhkBundle.message("compile.start", name) + "\n"
                + withLineBreak(output.getStdout()) + withLineBreak(output.getStderr());
        if (exe == null) return failedCompilation(executor, output, compileOutput);

        LocalFileSystem.getInstance().refreshAndFindFileByPath(FileUtil.toSystemIndependentName(exe));
        AhkRunConfiguration.ExeConsole mode = configuration.getExeConsole();
        GeneralCommandLine commandLine = exeCommandLine(exe, configuration.getScriptParameters(),
                configuration.getEffectiveWorkingDirectory(), mode);
        String started = AhkBundle.message(mode == AhkRunConfiguration.ExeConsole.WINDOW
                ? "run.compiled.window" : "run.compiled.terminal", exe) + "\n";
        if (mode == AhkRunConfiguration.ExeConsole.TERMINAL) {
            KillableProcessHandler handler = new KillableProcessHandler(
                    new PtyCommandLine(commandLine).withConsoleMode(false));
            handler.setHasPty(true);
            ProcessTerminatedListener.attach(handler);
            TerminalExecutionConsole console = new TerminalExecutionConsole(project, handler);
            console.addMessageFilter(new AhkErrorFilter(project));
            console.print(toCrLf(compileOutput + "\n" + started + "\n"), ConsoleViewContentType.SYSTEM_OUTPUT);
            return new DefaultExecutionResult(console, handler);
        }
        OSProcessHandler handler = ProcessHandlerFactory.getInstance().createColoredProcessHandler(commandLine);
        ProcessTerminatedListener.attach(handler);
        ConsoleView console = createConsole(executor);
        if (console == null) throw new ExecutionException(started);
        console.print(compileOutput + "\n" + started, ConsoleViewContentType.SYSTEM_OUTPUT);
        console.attachToProcess(handler);
        return new DefaultExecutionResult(console, handler);
    }

    /**
     * Result for a failed compilation: a console with the output of Ahk2Exe (errors clickable) and a summary, and a
     * process handler that terminates at once with the exit code of Ahk2Exe.
     */
    private @NotNull ExecutionResult failedCompilation(@NotNull Executor executor, @NotNull ProcessOutput output,
                                                       @NotNull String compileOutput) throws ExecutionException {
        String summary = output.isCancelled() ? AhkBundle.message("compile.cancelled")
                : output.isTimeout() ? AhkBundle.message("compile.timeout", AhkCompiler.TIMEOUT_MS / 1000)
                : AhkBundle.message("compile.error", output.getExitCode());
        ConsoleView console = createConsole(executor);
        if (console == null) throw new ExecutionException(summary);
        console.print(compileOutput, ConsoleViewContentType.NORMAL_OUTPUT);
        console.print("\n" + summary + "\n", ConsoleViewContentType.ERROR_OUTPUT);
        int exitCode = output.getExitCode() == 0 ? 1 : output.getExitCode();
        ProcessHandler failed = new NopProcessHandler() {
            @Override
            public void startNotify() {
                super.startNotify();
                notifyProcessTerminated(exitCode);
            }
        };
        console.attachToProcess(failed);
        return new DefaultExecutionResult(console, failed);
    }

    /**
     * Command line of the compiled executable with the parameters in the working directory: directly (terminal) or,
     * for a separate console window, PowerShell with {@link #startProcessScript} as {@code -EncodedCommand} (no
     * quoting problems). {@code cmd /c start /wait} was not usable: it does not return the exit code, and the
     * platform escapes an appended {@code & exit}.
     */
    public static @NotNull GeneralCommandLine exeCommandLine(@NotNull String exe, @NotNull String parameters,
                                                             @NotNull String workingDirectory,
                                                             @NotNull AhkRunConfiguration.ExeConsole console) {
        GeneralCommandLine commandLine;
        if (console == AhkRunConfiguration.ExeConsole.WINDOW) {
            commandLine = new GeneralCommandLine("powershell.exe", "-NoProfile", "-NonInteractive", "-EncodedCommand",
                    Base64.getEncoder().encodeToString(startProcessScript(exe, parameters, workingDirectory)
                            .getBytes(StandardCharsets.UTF_16LE)));
        } else {
            commandLine = new GeneralCommandLine(FileUtil.toSystemDependentName(exe));
            commandLine.getParametersList().addParametersString(parameters);
        }
        if (!workingDirectory.isEmpty()) commandLine.setWorkDirectory(FileUtil.toSystemDependentName(workingDirectory));
        commandLine.setCharset(systemCharset());
        return commandLine;
    }

    /**
     * PowerShell script that starts the executable in a new console window, waits for it and exits with its exit
     * code. The parameters are passed as one string, exactly as written in the run configuration.
     */
    public static @NotNull String startProcessScript(@NotNull String exe, @NotNull String parameters,
                                                     @NotNull String workingDirectory) {
        StringBuilder script = new StringBuilder("$p = Start-Process -FilePath ")
                .append(psQuote(FileUtil.toSystemDependentName(exe)));
        if (!parameters.isBlank()) script.append(" -ArgumentList ").append(psQuote(parameters));
        if (!workingDirectory.isEmpty()) {
            script.append(" -WorkingDirectory ").append(psQuote(FileUtil.toSystemDependentName(workingDirectory)));
        }
        return script.append(" -Wait -PassThru; exit $p.ExitCode").toString();
    }

    /** PowerShell string literal in single quotes (a quote inside is doubled). */
    private static @NotNull String psQuote(@NotNull String text) {
        return "'" + text.replace("'", "''") + "'";
    }

    /** The text with a final line break (empty stays empty). */
    private static @NotNull String withLineBreak(@NotNull String text) {
        return text.isEmpty() || text.endsWith("\n") ? text : text + "\n";
    }

    /** Line breaks as CR LF (the terminal needs the carriage return). */
    private static @NotNull String toCrLf(@NotNull String text) {
        return text.replace("\r\n", "\n").replace("\n", "\r\n");
    }

    /** Starts the AutoHotkey process. */
    @Override
    protected @NotNull ProcessHandler startProcess() throws ExecutionException {
        String exe = AhkSettings.getInstance().findAutoHotkey(configuration.getScriptPath());
        if (exe == null) {
            throw new ExecutionException(AhkSettings.getInstance().notFoundMessage(configuration.getScriptPath()));
        }
        GeneralCommandLine commandLine = createCommandLine(exe, configuration.getScriptPath(),
                configuration.getScriptParameters(), configuration.getEffectiveWorkingDirectory());
        OSProcessHandler handler = ProcessHandlerFactory.getInstance().createColoredProcessHandler(commandLine);
        ProcessTerminatedListener.attach(handler);
        return handler;
    }

    /** Command line {@code exe [launcher.ahk] /ErrorStdOut script parameters…} in the working directory. */
    public static @NotNull GeneralCommandLine createCommandLine(@NotNull String exe, @NotNull String script,
                                                         @NotNull String parameters, @NotNull String workingDirectory) {
        return createCommandLine(exe, List.of("/ErrorStdOut"), script, parameters, workingDirectory);
    }

    /**
     * Command line {@code exe [launcher.ahk] switches… script parameters…} in the working directory (empty = current
     * directory of the IDE). Also used by the validation ({@code /ErrorStdOut /validate}).
     */
    public static @NotNull GeneralCommandLine createCommandLine(@NotNull String exe, @NotNull List<String> switches,
                                                         @NotNull String script, @NotNull String parameters,
                                                         @NotNull String workingDirectory) {
        GeneralCommandLine commandLine = new GeneralCommandLine(FileUtil.toSystemDependentName(exe));
        Path launcher = AhkSettings.launcherScript(Path.of(exe));
        if (launcher != null) commandLine.addParameter(launcher.toString());
        commandLine.addParameters(switches);
        commandLine.addParameter(FileUtil.toSystemDependentName(script));
        commandLine.getParametersList().addParametersString(parameters);
        if (!workingDirectory.isEmpty()) commandLine.setWorkDirectory(FileUtil.toSystemDependentName(workingDirectory));
        commandLine.setCharset(systemCharset());
        return commandLine;
    }

    /** Code page of the system ({@code native.encoding}), otherwise the JVM default. */
    public static Charset systemCharset() {
        try {
            String name = System.getProperty("native.encoding");
            if (name != null) return Charset.forName(name);
        } catch (RuntimeException ignored) {
            // unknown code page: JVM default
        }
        return Charset.defaultCharset();
    }
}
