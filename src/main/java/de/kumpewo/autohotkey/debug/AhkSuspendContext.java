package de.kumpewo.autohotkey.debug;

import com.intellij.xdebugger.frame.XExecutionStack;
import com.intellij.xdebugger.frame.XStackFrame;
import com.intellij.xdebugger.frame.XSuspendContext;
import de.kumpewo.autohotkey.debug.dbgp.DbgpStackFrame;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;

/**
 * A stop of the script: one execution stack (AutoHotkey has one stack of pseudo-threads; the bottom frame names
 * the thread, e.g. {@code Auto-execute thread}).
 */
final class AhkSuspendContext extends XSuspendContext {

    private final AhkExecutionStack stack;

    AhkSuspendContext(@NotNull AhkDebugController controller, @NotNull AhkDebugController.Suspension suspension) {
        stack = new AhkExecutionStack(controller, suspension);
    }

    /** The only stack. */
    @Override
    public @NotNull XExecutionStack getActiveExecutionStack() {
        return stack;
    }

    /** The only stack. */
    @Override
    public XExecutionStack @NotNull [] getExecutionStacks() {
        return new XExecutionStack[]{stack};
    }

    /** The frames of the stop. */
    static final class AhkExecutionStack extends XExecutionStack {

        private final List<AhkStackFrame> frames = new ArrayList<>();

        AhkExecutionStack(@NotNull AhkDebugController controller, @NotNull AhkDebugController.Suspension suspension) {
            super(threadName(suspension.frames()));
            for (DbgpStackFrame frame : suspension.frames()) {
                frames.add(new AhkStackFrame(controller, suspension.epoch(), frame));
            }
        }

        /** Name of the stack: {@code where} of the bottom frame. */
        private static @NotNull String threadName(@NotNull List<DbgpStackFrame> frames) {
            return frames.isEmpty() ? "AutoHotkey" : frames.get(frames.size() - 1).where();
        }

        /** The current frame. */
        @Override
        public @Nullable XStackFrame getTopFrame() {
            return frames.isEmpty() ? null : frames.get(0);
        }

        /** All frames from the index on. */
        @Override
        public void computeStackFrames(int firstFrameIndex, @NotNull XStackFrameContainer container) {
            List<AhkStackFrame> rest = firstFrameIndex >= frames.size() ? List.of()
                    : frames.subList(firstFrameIndex, frames.size());
            container.addStackFrames(new ArrayList<>(rest), true);
        }
    }
}
