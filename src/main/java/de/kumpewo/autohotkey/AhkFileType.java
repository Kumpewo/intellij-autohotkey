package de.kumpewo.autohotkey;

import com.intellij.openapi.fileTypes.LanguageFileType;
import com.intellij.openapi.util.NlsContexts;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import javax.swing.*;

/** File type for AutoHotkey v2 scripts (extension {@code .ahk}, registered in {@code plugin.xml}). */
public final class AhkFileType extends LanguageFileType {
    public static final AhkFileType INSTANCE = new AhkFileType();

    private AhkFileType() {
        super(AhkLanguage.INSTANCE);
    }

    /** Internal name of the file type (also used in {@code plugin.xml}). */
    @Override
    public @NotNull String getName() {
        return "AutoHotkey";
    }

    /** Description shown in the settings under "File Types". */
    @Override
    public @NotNull @NlsContexts.Label String getDescription() {
        return "AutoHotkey v2 script file";
    }

    /** Default extension without the dot. */
    @Override
    public @NotNull String getDefaultExtension() {
        return "ahk";
    }

    /** Icon of AutoHotkey files in the project view and on editor tabs. */
    @Override
    public @Nullable Icon getIcon() {
        return AhkIcons.FILE;
    }
}
