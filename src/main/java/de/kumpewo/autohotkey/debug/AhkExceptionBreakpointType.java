package de.kumpewo.autohotkey.debug;

import com.intellij.openapi.project.Project;
import com.intellij.ui.components.JBCheckBox;
import com.intellij.util.ui.FormBuilder;
import com.intellij.xdebugger.breakpoints.XBreakpoint;
import com.intellij.xdebugger.breakpoints.XBreakpointProperties;
import com.intellij.xdebugger.breakpoints.XBreakpointType;
import com.intellij.xdebugger.breakpoints.ui.XBreakpointCustomPropertiesPanel;
import de.kumpewo.autohotkey.AhkBundle;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import javax.swing.JComponent;

/**
 * "AutoHotkey Exceptions": one breakpoint for all errors (the engine knows no error classes), disabled by default.
 * The engine stops at caught and uncaught errors alike; the properties choose which of them suspend
 * (default: uncaught only), the debug process continues at the others.
 */
public final class AhkExceptionBreakpointType
        extends XBreakpointType<XBreakpoint<AhkExceptionBreakpointType.Properties>, AhkExceptionBreakpointType.Properties> {

    /** Registered in plugin.xml ({@code xdebugger.breakpointType}). */
    public AhkExceptionBreakpointType() {
        super("autohotkey-exception", AhkBundle.message("debug.exception.type"));
    }

    /** Which errors suspend. */
    public static final class Properties extends XBreakpointProperties<Properties> {
        /** Suspend at errors caught by {@code try}. */
        public boolean caught;
        /** Suspend at uncaught errors (before AutoHotkey shows its error dialog). */
        public boolean uncaught = true;

        /** The state to store (this object). */
        @Override
        public @NotNull Properties getState() {
            return this;
        }

        /** Takes over the stored state. */
        @Override
        public void loadState(@NotNull Properties state) {
            caught = state.caught;
            uncaught = state.uncaught;
        }
    }

    /** Text in the breakpoints dialog. */
    @Override
    public @NotNull String getDisplayText(@NotNull XBreakpoint<Properties> breakpoint) {
        Properties properties = breakpoint.getProperties();
        String key = properties.caught && properties.uncaught ? "debug.exception.all"
                : properties.caught ? "debug.exception.caught" : "debug.exception.uncaught";
        return AhkBundle.message(key);
    }

    /** Default properties (uncaught only). */
    @Override
    public @Nullable Properties createProperties() {
        return new Properties();
    }

    /** The one breakpoint of this type, disabled. */
    @Override
    public @Nullable XBreakpoint<Properties> createDefaultBreakpoint(@NotNull XBreakpointCreator<Properties> creator) {
        XBreakpoint<Properties> breakpoint = creator.createBreakpoint(new Properties());
        breakpoint.setEnabled(false);
        return breakpoint;
    }

    /** No further breakpoints of this type. */
    @Override
    public boolean isAddBreakpointButtonVisible() {
        return false;
    }

    /** Check boxes "Caught" and "Uncaught". */
    @Override
    public @Nullable XBreakpointCustomPropertiesPanel<XBreakpoint<Properties>> createCustomPropertiesPanel(
            @NotNull Project project) {
        return new XBreakpointCustomPropertiesPanel<>() {
            private final JBCheckBox caught = new JBCheckBox(AhkBundle.message("debug.exception.option.caught"));
            private final JBCheckBox uncaught = new JBCheckBox(AhkBundle.message("debug.exception.option.uncaught"));

            @Override
            public @NotNull JComponent getComponent() {
                return FormBuilder.createFormBuilder().addComponent(caught).addComponent(uncaught).getPanel();
            }

            @Override
            public void saveTo(@NotNull XBreakpoint<Properties> breakpoint) {
                breakpoint.getProperties().caught = caught.isSelected();
                breakpoint.getProperties().uncaught = uncaught.isSelected();
            }

            @Override
            public void loadFrom(@NotNull XBreakpoint<Properties> breakpoint) {
                caught.setSelected(breakpoint.getProperties().caught);
                uncaught.setSelected(breakpoint.getProperties().uncaught);
            }
        };
    }

    /** True if a stop with the reason suspends for these properties. */
    static boolean suspends(@NotNull Properties properties, @NotNull String reason) {
        return AhkDebugController.REASON_CAUGHT.equals(reason) ? properties.caught : properties.uncaught;
    }
}
