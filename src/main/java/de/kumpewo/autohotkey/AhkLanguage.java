package de.kumpewo.autohotkey;

import com.intellij.lang.Language;

/** The AutoHotkey v2 language; every language extension in {@code plugin.xml} refers to its ID "AutoHotkey". */
public class AhkLanguage extends Language {

    public static final AhkLanguage INSTANCE = new AhkLanguage();

    private AhkLanguage() {
        super("AutoHotkey");
    }
}
