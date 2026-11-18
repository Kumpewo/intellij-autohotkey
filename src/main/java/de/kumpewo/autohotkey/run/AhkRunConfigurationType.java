package de.kumpewo.autohotkey.run;

import de.kumpewo.autohotkey.AhkBundle;
import com.intellij.execution.configurations.ConfigurationFactory;
import com.intellij.execution.configurations.ConfigurationTypeBase;
import com.intellij.execution.configurations.ConfigurationTypeUtil;
import com.intellij.execution.configurations.RunConfiguration;
import com.intellij.openapi.components.BaseState;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.util.NotNullLazyValue;
import de.kumpewo.autohotkey.AhkIcons;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * Type of the run configuration "AutoHotkey": runs a script with AutoHotkey.
 */
public final class AhkRunConfigurationType extends ConfigurationTypeBase {

    public static final String ID = "AutoHotkeyRunConfiguration";

    public AhkRunConfigurationType() {
        super(ID, "AutoHotkey", AhkBundle.message("run.type.description"),
                NotNullLazyValue.createValue(() -> AhkIcons.INTERPRETER));
        addFactory(new Factory(this));
    }

    /** The registered instance. */
    public static AhkRunConfigurationType getInstance() {
        return ConfigurationTypeUtil.findConfigurationType(AhkRunConfigurationType.class);
    }

    /** The only factory of this type. */
    public @NotNull ConfigurationFactory getFactory() {
        return getConfigurationFactories()[0];
    }

    /** Creates AutoHotkey run configurations. */
    private static final class Factory extends ConfigurationFactory {
        Factory(AhkRunConfigurationType type) {
            super(type);
        }

        /** Stable ID (stored in the run configurations of the project). */
        @Override
        public @NotNull String getId() {
            return "AutoHotkey";
        }

        /** The template from which new configurations are copied. */
        @Override
        public @NotNull RunConfiguration createTemplateConfiguration(@NotNull Project project) {
            return new AhkRunConfiguration(project, this, "AutoHotkey");
        }

        /** Class of the stored values. */
        @Override
        public @Nullable Class<? extends BaseState> getOptionsClass() {
            return AhkRunConfigurationOptions.class;
        }
    }
}
