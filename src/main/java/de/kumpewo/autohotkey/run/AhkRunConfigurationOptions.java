package de.kumpewo.autohotkey.run;

import com.intellij.execution.configurations.LocatableRunConfigurationOptions;
import com.intellij.openapi.components.StoredProperty;

/**
 * Stored values of the run configuration: script, parameters of the script, working directory (empty = directory
 * of the script), whether to compile the script with Ahk2Exe and run the executable, and where the executable runs.
 * Paths are system-independent (with {@code /}).
 */
public class AhkRunConfigurationOptions extends LocatableRunConfigurationOptions {

    private final StoredProperty<String> scriptPath = string("").provideDelegate(this, "scriptPath");
    private final StoredProperty<String> scriptParameters = string("").provideDelegate(this, "scriptParameters");
    private final StoredProperty<String> workingDirectory = string("").provideDelegate(this, "workingDirectory");
    private final StoredProperty<Boolean> compileFirst = property(false).provideDelegate(this, "compileFirst");
    private final StoredProperty<String> exeConsole = string("TERMINAL").provideDelegate(this, "exeConsole");

    /** Path of the script. */
    public String getScriptPath() {
        return scriptPath.getValue(this);
    }

    /** Sets the path of the script. */
    public void setScriptPath(String value) {
        scriptPath.setValue(this, value);
    }

    /** Parameters of the script. */
    public String getScriptParameters() {
        return scriptParameters.getValue(this);
    }

    /** Sets the parameters of the script. */
    public void setScriptParameters(String value) {
        scriptParameters.setValue(this, value);
    }

    /** Working directory (empty = directory of the script). */
    public String getWorkingDirectory() {
        return workingDirectory.getValue(this);
    }

    /** Sets the working directory. */
    public void setWorkingDirectory(String value) {
        workingDirectory.setValue(this, value);
    }

    /** True = compile with Ahk2Exe first and run the executable instead of the script. */
    public boolean isCompileFirst() {
        return compileFirst.getValue(this);
    }

    /** Sets whether to compile first. */
    public void setCompileFirst(boolean value) {
        compileFirst.setValue(this, value);
    }

    /** Where the compiled executable runs (name of an {@link AhkRunConfiguration.ExeConsole}). */
    public String getExeConsole() {
        return exeConsole.getValue(this);
    }

    /** Sets where the compiled executable runs. */
    public void setExeConsole(String value) {
        exeConsole.setValue(this, value);
    }
}
