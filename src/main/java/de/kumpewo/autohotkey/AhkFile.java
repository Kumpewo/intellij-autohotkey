package de.kumpewo.autohotkey;

import com.intellij.extapi.psi.PsiFileBase;
import com.intellij.openapi.fileTypes.FileType;
import com.intellij.psi.FileViewProvider;
import org.jetbrains.annotations.NotNull;

/** Root of the PSI tree of an AutoHotkey script (created by {@code AhkParserDefinition.createFile}). */
public class AhkFile extends PsiFileBase {

    public AhkFile(@NotNull FileViewProvider viewProvider) {
        super(viewProvider, AhkLanguage.INSTANCE);
    }

    /** Returns the AutoHotkey file type. */
    @NotNull
    @Override
    public FileType getFileType() {
        return AhkFileType.INSTANCE;
    }

    /** Debug name, shown in the PSI viewer and in the parser test output. */
    @Override
    public String toString() {
        return "AutoHotkey File";
    }

}
