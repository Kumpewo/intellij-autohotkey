package de.kumpewo.autohotkey.debug;

import com.intellij.icons.AllIcons;
import com.intellij.openapi.actionSystem.ActionUpdateThread;
import com.intellij.openapi.actionSystem.AnActionEvent;
import com.intellij.openapi.actionSystem.DefaultActionGroup;
import com.intellij.openapi.project.DumbAwareAction;
import com.intellij.execution.filters.TextConsoleBuilder;
import com.intellij.execution.filters.TextConsoleBuilderFactory;
import com.intellij.execution.process.ProcessHandler;
import com.intellij.execution.ui.ConsoleView;
import com.intellij.execution.ui.ConsoleViewContentType;
import com.intellij.execution.ui.ExecutionConsole;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.util.io.FileUtil;
import com.intellij.openapi.vfs.VfsUtilCore;
import com.intellij.util.concurrency.AppExecutorUtil;
import com.intellij.xdebugger.XDebugSession;
import com.intellij.xdebugger.XSourcePosition;
import com.intellij.xdebugger.XExpression;
import com.intellij.xdebugger.breakpoints.XBreakpoint;
import com.intellij.xdebugger.breakpoints.XBreakpointHandler;
import com.intellij.xdebugger.breakpoints.XBreakpointProperties;
import com.intellij.xdebugger.breakpoints.XLineBreakpoint;
import com.intellij.xdebugger.evaluation.XDebuggerEditorsProvider;
import com.intellij.xdebugger.frame.XSuspendContext;
import com.intellij.xdebugger.frame.XValueMarkerProvider;
import com.intellij.xdebugger.XDebugProcess;
import de.kumpewo.autohotkey.AhkBundle;
import de.kumpewo.autohotkey.debug.dbgp.DbgpConnection;
import de.kumpewo.autohotkey.debug.dbgp.DbgpServer;
import de.kumpewo.autohotkey.run.AhkErrorFilter;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.io.IOException;
import java.net.Socket;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

/**
 * The debug process of a session: connects the XDebugger UI with the {@link AhkDebugController}. The session
 * starts at once (console with the process output, load errors clickable); {@link #sessionInitialized()} waits in
 * the background for AutoHotkey to connect. If the process ends first (e.g. a syntax error: AutoHotkey then does
 * not connect), the session ends with the process; if it does not connect within {@link #CONNECT_TIMEOUT_MS}, the
 * process is stopped.
 */
public final class AhkDebugProcess extends XDebugProcess implements AhkDebugController.Callbacks {

    private static final Logger LOG = Logger.getInstance(AhkDebugProcess.class);

    /** How long to wait for AutoHotkey to connect. */
    public static final int CONNECT_TIMEOUT_MS = 10_000;

    /** How long to wait for the answer to {@code stop} before the process is killed. */
    private static final int STOP_TIMEOUT_MS = 2_000;

    private final DbgpServer server;
    private final ProcessHandler processHandler;
    private final @Nullable String note;
    private final AhkDebugController controller = new AhkDebugController(this);
    private final AhkBreakpointHandler breakpointHandler = new AhkBreakpointHandler();
    private final AhkExceptionBreakpointHandler exceptionHandler = new AhkExceptionBreakpointHandler();
    private final @Nullable Runnable requestConnection;
    private volatile @Nullable DbgpConnection connection;

    /**
     * Debug process for a started AutoHotkey process.
     *
     * @param server         listening socket (closed after the engine connected)
     * @param processHandler the AutoHotkey process started with {@code /Debug}
     * @param note           message printed first in the console (e.g. "compile first" is ignored), or null
     */
    AhkDebugProcess(@NotNull XDebugSession session, @NotNull DbgpServer server,
                    @NotNull ProcessHandler processHandler, @Nullable String note) {
        this(session, server, processHandler, note, null);
    }

    /**
     * Debug process; with {@code requestConnection} for a running script (attach): it is called when the socket
     * waits and asks the script to connect, the handler is an {@link AhkAttachedProcessHandler}.
     */
    AhkDebugProcess(@NotNull XDebugSession session, @NotNull DbgpServer server,
                    @NotNull ProcessHandler processHandler, @Nullable String note,
                    @Nullable Runnable requestConnection) {
        super(session);
        this.server = server;
        this.processHandler = processHandler;
        this.note = note;
        this.requestConnection = requestConnection;
    }

    /** True for a session attached to a running script (Stop detaches). */
    private boolean isAttached() {
        return processHandler instanceof AhkAttachedProcessHandler;
    }

    /** Editor for watches and evaluation (AutoHotkey file type). */
    @Override
    public @NotNull XDebuggerEditorsProvider getEditorsProvider() {
        return new AhkDebuggerEditorsProvider();
    }

    /** The handlers for AutoHotkey line and exception breakpoints. */
    @Override
    public XBreakpointHandler<?> @NotNull [] getBreakpointHandlers() {
        return new XBreakpointHandler<?>[]{breakpointHandler, exceptionHandler};
    }

    /** "Mark Object" by the address of an object. */
    @Override
    public @NotNull XValueMarkerProvider<?, ?> createValueMarkerProvider() {
        return new XValueMarkerProvider<AhkValue, String>(AhkValue.class) {
            @Override
            public boolean canMark(@NotNull AhkValue value) {
                return !value.address().isEmpty();
            }

            @Override
            public @NotNull String getMarker(@NotNull AhkValue value) {
                return value.address();
            }
        };
    }

    /** Adds the toolbar button "Detach" (the script continues without the debugger). */
    @Override
    public void registerAdditionalActions(@NotNull DefaultActionGroup leftToolbar,
                                          @NotNull DefaultActionGroup topToolbar,
                                          @NotNull DefaultActionGroup settings) {
        leftToolbar.add(new DumbAwareAction(AhkBundle.message("debug.detach"),
                AhkBundle.message("debug.detach.description"), AllIcons.Actions.Exit) {
            @Override
            public void actionPerformed(@NotNull AnActionEvent event) {
                detach();
            }

            @Override
            public void update(@NotNull AnActionEvent event) {
                DbgpConnection current = connection;
                event.getPresentation().setEnabled(current != null && !current.isClosed());
            }

            @Override
            public @NotNull ActionUpdateThread getActionUpdateThread() {
                return ActionUpdateThread.BGT;
            }
        });
    }

    /** Detaches: {@code detach}, then the IDE lets go of the process, which keeps running. */
    void detach() {
        closeServer();
        print(AhkBundle.message("debug.detached"), ConsoleViewContentType.SYSTEM_OUTPUT);
        controller.detach().whenComplete((result, error) -> processHandler.detachProcess());
    }

    /** The AutoHotkey process. */
    @Override
    protected @NotNull ProcessHandler doGetProcessHandler() {
        return processHandler;
    }

    /** Console attached to the process, with clickable load errors. */
    @Override
    public @NotNull ExecutionConsole createConsole() {
        TextConsoleBuilder builder = TextConsoleBuilderFactory.getInstance().createBuilder(getSession().getProject());
        builder.addFilter(new AhkErrorFilter(getSession().getProject()));
        ConsoleView console = builder.getConsole();
        if (note != null) console.print(note + "\n", ConsoleViewContentType.SYSTEM_OUTPUT);
        console.attachToProcess(processHandler);
        return console;
    }

    /** Waits in the background for AutoHotkey to connect, then starts the controller. */
    @Override
    public void sessionInitialized() {
        AppExecutorUtil.getAppExecutorService().execute(this::connect);
    }

    /** Accepts the connection of the engine (background thread). */
    private void connect() {
        Socket socket = null;
        try {
            if (requestConnection != null) requestConnection.run();
            socket = server.accept(CONNECT_TIMEOUT_MS, processHandler::isProcessTerminated);
        } catch (IOException e) {
            LOG.warn("Waiting for the AutoHotkey debugger failed", e);
        } finally {
            closeServer();
        }
        if (socket == null) {
            if (!processHandler.isProcessTerminated()) {
                print(AhkBundle.message("debug.no.connection", CONNECT_TIMEOUT_MS / 1000), ConsoleViewContentType.ERROR_OUTPUT);
                processHandler.destroyProcess();
            }
            return;
        }
        try {
            DbgpConnection dbgp = new DbgpConnection(socket, controller.listener());
            connection = dbgp;
            dbgp.start();
            dbgp.init().get(CONNECT_TIMEOUT_MS, TimeUnit.MILLISECONDS);
            controller.attach(dbgp);
        } catch (Exception e) {
            LOG.warn("Starting the AutoHotkey debug session failed", e);
            print(AhkBundle.message("debug.no.connection", CONNECT_TIMEOUT_MS / 1000), ConsoleViewContentType.ERROR_OUTPUT);
            processHandler.destroyProcess();
        }
    }

    /** Closes the listening socket. */
    private void closeServer() {
        try {
            server.close();
        } catch (IOException e) {
            LOG.debug("Closing the debugger socket failed", e);
        }
    }

    /** Prints a line into the debug console. */
    private void print(@NotNull String text, @NotNull ConsoleViewContentType type) {
        ConsoleView console = getSession().getConsoleView();
        if (console != null) console.print(text + "\n", type);
    }

    /** Continues ({@code run}). */
    @Override
    public void resume(@Nullable XSuspendContext context) {
        controller.resume();
    }

    /** Pauses the running script ({@code break}). */
    @Override
    public void startPausing() {
        controller.pause();
    }

    /** Step over. */
    @Override
    public void startStepOver(@Nullable XSuspendContext context) {
        controller.stepOver();
    }

    /** Step into. */
    @Override
    public void startStepInto(@Nullable XSuspendContext context) {
        controller.stepInto();
    }

    /** Step out. */
    @Override
    public void startStepOut(@Nullable XSuspendContext context) {
        controller.stepOut();
    }

    /** Run to the line (temporary breakpoint). */
    @Override
    public void runToPosition(@NotNull XSourcePosition position, @Nullable XSuspendContext context) {
        controller.runTo(FileUtil.toSystemDependentName(position.getFile().getPath()), position.getLine() + 1);
    }

    /**
     * Stops via DBGp and kills the process if it does not end in time. An attached session only detaches (the
     * script was not started by the IDE).
     */
    @Override
    public void stop() {
        if (isAttached()) {
            closeServer();
            controller.detach();
            return;
        }
        closeServer();
        controller.stop().whenComplete((result, error) -> AppExecutorUtil.getAppScheduledExecutorService().schedule(
                () -> {
                    if (!processHandler.isProcessTerminated()) processHandler.destroyProcess();
                }, STOP_TIMEOUT_MS, TimeUnit.MILLISECONDS));
        DbgpConnection current = connection;
        if (current != null) {
            AppExecutorUtil.getAppScheduledExecutorService().schedule(current::close, STOP_TIMEOUT_MS,
                    TimeUnit.MILLISECONDS);
        }
    }

    /**
     * Shows the stop. At a breakpoint the plugin first evaluates its condition and log expression (name paths
     * only; the platform does not evaluate them), then the IDE decides (e.g. a breakpoint that does not suspend).
     */
    @Override
    public void suspended(@NotNull AhkDebugController.Suspension suspension) {
        AhkSuspendContext context = new AhkSuspendContext(controller, suspension);
        if (suspension.isException()) {
            // the engine stops at every error; the exception breakpoint decides whether caught/uncaught suspend
            if (!(suspension.breakpoint() instanceof XBreakpoint<?> exception)
                    || !(exception.getProperties() instanceof AhkExceptionBreakpointType.Properties properties)
                    || !AhkExceptionBreakpointType.suspends(properties, suspension.reason())) {
                controller.resume();
                return;
            }
            int line = suspension.frames().isEmpty() ? 0 : suspension.frames().get(0).line();
            print(AhkBundle.message(AhkDebugController.REASON_CAUGHT.equals(suspension.reason())
                    ? "debug.exception.stopped.caught" : "debug.exception.stopped.uncaught", line),
                    ConsoleViewContentType.SYSTEM_OUTPUT);
            reached(exception, suspension, context);
            return;
        }
        if (suspension.frames().isEmpty()) print(AhkBundle.message("debug.idle"), ConsoleViewContentType.SYSTEM_OUTPUT);
        if (!(suspension.breakpoint() instanceof XLineBreakpoint<?> breakpoint)) {
            getSession().positionReached(context);
            return;
        }
        reached(breakpoint, suspension, context);
    }

    /** Evaluates condition and log expression of the breakpoint, then lets the IDE decide. */
    private void reached(@NotNull XBreakpoint<?> breakpoint, @NotNull AhkDebugController.Suspension suspension,
                         @NotNull AhkSuspendContext context) {
        int epoch = suspension.epoch();
        condition(epoch, breakpoint.getConditionExpression()).thenCompose(suspend -> {
            if (!suspend) {
                controller.resume();
                return CompletableFuture.completedFuture(null);
            }
            return logText(epoch, breakpoint.getLogExpressionObject()).thenAccept(log -> {
                if (!getSession().breakpointReached(breakpoint, log, context)) controller.resume();
            });
        });
    }

    /**
     * True if the breakpoint suspends: no condition, a condition that is no name path (ignored, see the console),
     * or a name path whose value is true ({@link AhkValueText#isTrue}). An error while evaluating suspends.
     */
    private @NotNull CompletableFuture<Boolean> condition(int epoch, @Nullable XExpression expression) {
        String path = expression == null ? null : AhkNamePath.normalize(expression.getExpression());
        if (path == null) return CompletableFuture.completedFuture(true);
        return controller.resolve(epoch, path, 0)
                .thenApply(resolved -> AhkValueText.isTrue(resolved.property()))
                .exceptionally(error -> true);
    }

    /** Text of the log expression ({@code path = value}), or {@code null} without a usable one. */
    private @NotNull CompletableFuture<@Nullable String> logText(int epoch, @Nullable XExpression expression) {
        String path = expression == null ? null : AhkNamePath.normalize(expression.getExpression());
        if (path == null) return CompletableFuture.completedFuture(null);
        String shown = expression.getExpression().trim();
        return controller.resolve(epoch, path, 0)
                .thenApply(resolved -> shown + " = " + AhkValueText.display(resolved.property()))
                .exceptionally(error -> shown + ": " + AhkStackFrame.message(error));
    }

    /** True if the expression is absent, empty or a name path (the only form the plugin can evaluate). */
    private static boolean usable(@Nullable XExpression expression) {
        return expression == null || expression.getExpression().isBlank()
                || AhkNamePath.isPath(expression.getExpression());
    }

    /** Marks the breakpoint as accepted by the engine. */
    @Override
    public void breakpointVerified(@NotNull Object key) {
        if (key instanceof XLineBreakpoint<?> breakpoint) getSession().setBreakpointVerified(breakpoint);
    }

    /** Marks the breakpoint as rejected, with the reason (for the exception breakpoint a console line). */
    @Override
    public void breakpointInvalid(@NotNull Object key, @NotNull String message) {
        if (key instanceof XLineBreakpoint<?> breakpoint) {
            getSession().setBreakpointInvalid(breakpoint, message);
        } else {
            print(message, ConsoleViewContentType.ERROR_OUTPUT);
        }
    }

    /** Prints OutputDebug text into the debug console. */
    @Override
    public void debugOutput(@NotNull String text) {
        ConsoleView console = getSession().getConsoleView();
        if (console != null) console.print(text.endsWith("\n") ? text : text + "\n", ConsoleViewContentType.LOG_DEBUG_OUTPUT);
    }

    /**
     * The connection ended: an attached session ends now (the script keeps running); otherwise the session ends
     * with the process (AutoHotkey exits after stop or at the end of the script).
     */
    @Override
    public void ended() {
        if (processHandler instanceof AhkAttachedProcessHandler attached) attached.connectionEnded();
    }

    /** Passes the AutoHotkey exception breakpoint to the controller. */
    private final class AhkExceptionBreakpointHandler
            extends XBreakpointHandler<XBreakpoint<AhkExceptionBreakpointType.Properties>> {

        AhkExceptionBreakpointHandler() {
            super(AhkExceptionBreakpointType.class);
        }

        /** Sets the exception breakpoint in the engine. */
        @Override
        public void registerBreakpoint(@NotNull XBreakpoint<AhkExceptionBreakpointType.Properties> breakpoint) {
            controller.setExceptionBreakpoint(breakpoint);
        }

        /** Removes the exception breakpoint. */
        @Override
        public void unregisterBreakpoint(@NotNull XBreakpoint<AhkExceptionBreakpointType.Properties> breakpoint,
                                         boolean temporary) {
            controller.removeExceptionBreakpoint();
        }
    }

    /** Passes AutoHotkey line breakpoints to the controller. */
    private final class AhkBreakpointHandler extends XBreakpointHandler<XLineBreakpoint<XBreakpointProperties<?>>> {

        AhkBreakpointHandler() {
            super(AhkLineBreakpointType.class);
        }

        /** Sends the breakpoint (with a console note if its condition or log expression is not a name path). */
        @Override
        public void registerBreakpoint(@NotNull XLineBreakpoint<XBreakpointProperties<?>> breakpoint) {
            String url = breakpoint.getFileUrl();
            String path = FileUtil.toSystemDependentName(VfsUtilCore.urlToPath(url));
            if (!usable(breakpoint.getConditionExpression()) || !usable(breakpoint.getLogExpressionObject())) {
                print(AhkBundle.message("debug.breakpoint.condition.ignored", breakpoint.getLine() + 1),
                        ConsoleViewContentType.SYSTEM_OUTPUT);
            }
            controller.addBreakpoint(new AhkDebugController.LineBreakpoint(breakpoint, path, breakpoint.getLine() + 1));
        }

        /** Removes the breakpoint. */
        @Override
        public void unregisterBreakpoint(@NotNull XLineBreakpoint<XBreakpointProperties<?>> breakpoint,
                                         boolean temporary) {
            controller.removeBreakpoint(breakpoint);
        }
    }
}
