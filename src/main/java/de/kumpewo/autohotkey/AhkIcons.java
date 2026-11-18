package de.kumpewo.autohotkey;

import com.intellij.openapi.util.IconLoader;

import javax.swing.*;

/**
 * Icons of the plugin: the file icon for {@code .ahk} files and the program icon of the interpreter for things that
 * run or use AutoHotkey (run configuration type; the menu actions reference the same file directly in
 * {@code plugin.xml}).
 */
public class AhkIcons {
    /** File icon of {@code .ahk} files. */
    public static final Icon FILE = IconLoader.getIcon("/icons/autohotkey.png", AhkIcons.class);
    /** Program icon of {@code AutoHotkey64.exe}. */
    public static final Icon INTERPRETER = IconLoader.getIcon("/icons/autohotkey-action.png", AhkIcons.class);
}
