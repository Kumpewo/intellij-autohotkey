package de.kumpewo.autohotkey.run;

import com.intellij.execution.actions.ConfigurationContext;
import com.intellij.execution.actions.LazyRunConfigurationProducer;
import com.intellij.execution.configurations.ConfigurationFactory;
import com.intellij.openapi.util.Ref;
import com.intellij.openapi.util.io.FileUtil;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.psi.PsiElement;
import com.intellij.psi.PsiFile;
import de.kumpewo.autohotkey.AhkFile;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * "Run 'file.ahk'" in the context menu of the editor and the project view (Ctrl+Shift+F10). If there is already a
 * configuration for this script (e.g. one created by hand for the main script), it is reused.
 */
public final class AhkRunConfigurationProducer extends LazyRunConfigurationProducer<AhkRunConfiguration> {

    /** The factory of the AutoHotkey run configuration type. */
    @Override
    public @NotNull ConfigurationFactory getConfigurationFactory() {
        return AhkRunConfigurationType.getInstance().getFactory();
    }

    /** Creates a configuration for the AutoHotkey file of the context. */
    @Override
    protected boolean setupConfigurationFromContext(@NotNull AhkRunConfiguration configuration,
                                                    @NotNull ConfigurationContext context,
                                                    @NotNull Ref<PsiElement> sourceElement) {
        PsiFile file = scriptFile(context);
        if (file == null) return false;
        sourceElement.set(file);
        configuration.setScriptPath(file.getVirtualFile().getPath());
        configuration.setGeneratedName();
        return true;
    }

    /** An existing configuration matches if it runs the same script. */
    @Override
    public boolean isConfigurationFromContext(@NotNull AhkRunConfiguration configuration,
                                              @NotNull ConfigurationContext context) {
        PsiFile file = scriptFile(context);
        return file != null && FileUtil.pathsEqual(file.getVirtualFile().getPath(), configuration.getScriptPath());
    }

    /** The AutoHotkey file of the context, or {@code null}. */
    private static @Nullable PsiFile scriptFile(@NotNull ConfigurationContext context) {
        PsiElement element = context.getPsiLocation();
        if (element == null) return null;
        PsiFile file = element.getContainingFile();
        if (!(file instanceof AhkFile)) return null;
        VirtualFile virtualFile = file.getVirtualFile();
        return virtualFile == null || virtualFile.isDirectory() ? null : file;
    }
}
