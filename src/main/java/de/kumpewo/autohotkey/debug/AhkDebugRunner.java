package de.kumpewo.autohotkey.debug;

import com.intellij.execution.ExecutionException;
import com.intellij.execution.configurations.GeneralCommandLine;
import com.intellij.execution.configurations.RunProfile;
import com.intellij.execution.configurations.RunProfileState;
import com.intellij.execution.configurations.RunnerSettings;
import com.intellij.execution.executors.DefaultDebugExecutor;
import com.intellij.execution.process.KillableColoredProcessHandler;
import com.intellij.execution.process.ProcessHandler;
import com.intellij.execution.process.ProcessTerminatedListener;
import com.intellij.execution.runners.ExecutionEnvironment;
import com.intellij.execution.runners.GenericProgramRunner;
import com.intellij.execution.ui.RunContentDescriptor;
import com.intellij.xdebugger.XDebugProcess;
import com.intellij.xdebugger.XDebugProcessStarter;
import com.intellij.xdebugger.XDebugSession;
import com.intellij.xdebugger.XDebuggerManager;
import de.kumpewo.autohotkey.AhkBundle;
import de.kumpewo.autohotkey.debug.dbgp.DbgpServer;
import de.kumpewo.autohotkey.run.AhkRunConfiguration;
import de.kumpewo.autohotkey.run.AhkRunState;
import de.kumpewo.autohotkey.settings.AhkSettings;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.io.IOException;
import java.util.List;

/**
 * Debug button for AutoHotkey run configurations: opens the DBGp socket, starts the v2 interpreter (never the
 * launcher, {@link AhkSettings#findInterpreter}) with {@code /ErrorStdOut /Debug=127.0.0.1:port script parameters}
 * in the working directory of the configuration and starts a debug session ({@link AhkDebugProcess}). With
 * "compile first" the script is debugged, not the executable (the console says so).
 */
public final class AhkDebugRunner extends GenericProgramRunner<RunnerSettings> {

    /** Id of the runner. */
    @Override
    public @NotNull String getRunnerId() {
        return "AutoHotkeyDebugRunner";
    }

    /** Debug executor and AutoHotkey run configuration. */
    @Override
    public boolean canRun(@NotNull String executorId, @NotNull RunProfile profile) {
        return DefaultDebugExecutor.EXECUTOR_ID.equals(executorId) && profile instanceof AhkRunConfiguration;
    }

    /** Starts AutoHotkey with the debugger switch and the debug session. */
    @Override
    protected @Nullable RunContentDescriptor doExecute(@NotNull RunProfileState state,
                                                       @NotNull ExecutionEnvironment environment)
            throws ExecutionException {
        AhkRunConfiguration configuration = (AhkRunConfiguration) environment.getRunProfile();
        String script = configuration.getScriptPath();
        String exe = AhkSettings.getInstance().findInterpreter(script);
        if (exe == null) throw new ExecutionException(AhkBundle.message("run.autohotkey.not.found"));
        DbgpServer server;
        try {
            server = DbgpServer.open();
        } catch (IOException e) {
            throw new ExecutionException(AhkBundle.message("debug.socket.failed", e.getMessage()), e);
        }
        ProcessHandler handler;
        try {
            GeneralCommandLine commandLine = AhkRunState.createCommandLine(exe,
                    List.of("/ErrorStdOut", server.debugSwitch()), script, configuration.getScriptParameters(),
                    configuration.getEffectiveWorkingDirectory());
            handler = new KillableColoredProcessHandler(commandLine);
        } catch (ExecutionException | RuntimeException e) {
            closeQuietly(server);
            throw e;
        }
        ProcessTerminatedListener.attach(handler);
        String note = configuration.isCompileFirst() ? AhkBundle.message("debug.compile.first.ignored") : null;
        XDebugSession session = XDebuggerManager.getInstance(environment.getProject()).startSession(environment,
                new XDebugProcessStarter() {
                    @Override
                    public @NotNull XDebugProcess start(@NotNull XDebugSession session) {
                        return new AhkDebugProcess(session, server, handler, note);
                    }
                });
        return session.getRunContentDescriptor();
    }

    /** Closes the socket after a failed start. */
    private static void closeQuietly(@NotNull DbgpServer server) {
        try {
            server.close();
        } catch (IOException ignored) {
            // nothing to do
        }
    }
}
