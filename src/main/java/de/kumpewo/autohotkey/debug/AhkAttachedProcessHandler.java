package de.kumpewo.autohotkey.debug;

import com.intellij.execution.process.ProcessHandler;
import org.jetbrains.annotations.Nullable;

import java.io.OutputStream;

/**
 * Process handler of a debug session attached to a running script: the IDE did not start the process, so Stop
 * detaches (the debug process sends {@code detach}) and the script keeps running.
 */
final class AhkAttachedProcessHandler extends ProcessHandler {

    /** Ends the session (the debug process has detached). */
    @Override
    protected void destroyProcessImpl() {
        notifyProcessDetached();
    }

    /** Ends the session without touching the script. */
    @Override
    protected void detachProcessImpl() {
        notifyProcessDetached();
    }

    /** Stop means detach. */
    @Override
    public boolean detachIsDefault() {
        return true;
    }

    /** No input. */
    @Override
    public @Nullable OutputStream getProcessInput() {
        return null;
    }

    /** Called when the connection ended: the session ends. */
    void connectionEnded() {
        if (!isProcessTerminated() && !isProcessTerminating()) notifyProcessDetached();
    }
}
