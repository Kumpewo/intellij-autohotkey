package de.kumpewo.autohotkey.debug;

import com.intellij.openapi.fileTypes.FileType;
import com.intellij.openapi.project.Project;
import com.intellij.psi.PsiElement;
import com.intellij.psi.PsiFile;
import com.intellij.psi.PsiFileFactory;
import com.intellij.xdebugger.evaluation.XDebuggerEditorsProviderBase;
import de.kumpewo.autohotkey.AhkFileType;
import de.kumpewo.autohotkey.AhkLanguage;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * Editor of the debugger (watches, evaluation): an AutoHotkey file, so that highlighting and completion work.
 */
final class AhkDebuggerEditorsProvider extends XDebuggerEditorsProviderBase {

    /** AutoHotkey. */
    @Override
    public @NotNull FileType getFileType() {
        return AhkFileType.INSTANCE;
    }

    /** A light AutoHotkey file with the expression. */
    @Override
    protected PsiFile createExpressionCodeFragment(@NotNull Project project, @NotNull String text,
                                                   @Nullable PsiElement context, boolean isPhysical) {
        return PsiFileFactory.getInstance(project).createFileFromText("fragment.ahk", AhkLanguage.INSTANCE, text,
                isPhysical, false);
    }
}
