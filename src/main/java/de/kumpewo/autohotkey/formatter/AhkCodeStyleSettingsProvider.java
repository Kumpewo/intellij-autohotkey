package de.kumpewo.autohotkey.formatter;

import com.intellij.application.options.CodeStyleAbstractConfigurable;
import com.intellij.application.options.CodeStyleAbstractPanel;
import com.intellij.application.options.TabbedLanguageCodeStylePanel;
import com.intellij.psi.codeStyle.CodeStyleConfigurable;
import com.intellij.psi.codeStyle.CodeStyleSettings;
import com.intellij.psi.codeStyle.CodeStyleSettingsProvider;
import com.intellij.psi.codeStyle.CustomCodeStyleSettings;
import de.kumpewo.autohotkey.AhkLanguage;
import org.jetbrains.annotations.NotNull;

/** Registers the page "Settings > Editor > Code Style > AutoHotkey". The options come from the LanguageCodeStyleSettingsProvider. */
final class AhkCodeStyleSettingsProvider extends CodeStyleSettingsProvider {

    /** The AHK-specific options. */
    @Override
    public CustomCodeStyleSettings createCustomSettings(@NotNull CodeStyleSettings settings) {
        return new AhkCodeStyleSettings(settings);
    }

    /** Name of the page. */
    @Override
    public String getConfigurableDisplayName() {
        return "AutoHotkey";
    }

    /** The language of the page (gives the configurable its id; without it the platform logs an error). */
    @Override
    public com.intellij.lang.Language getLanguage() {
        return AhkLanguage.INSTANCE;
    }

    /** The page with the standard tabs plus the tab "Case". */
    @NotNull
    @Override
    public CodeStyleConfigurable createConfigurable(@NotNull CodeStyleSettings settings,
                                                    @NotNull CodeStyleSettings modelSettings) {
        return new CodeStyleAbstractConfigurable(settings, modelSettings, getConfigurableDisplayName()) {
            @Override
            protected @NotNull CodeStyleAbstractPanel createPanel(@NotNull CodeStyleSettings settings) {
                return new AhkCodeStyleMainPanel(getCurrentSettings(), settings);
            }
        };
    }

    /** Tabbed panel of the code style page. */
    private static class AhkCodeStyleMainPanel extends TabbedLanguageCodeStylePanel {
        AhkCodeStyleMainPanel(CodeStyleSettings currentSettings, CodeStyleSettings settings) {
            super(AhkLanguage.INSTANCE, currentSettings, settings);
        }

        /** Standard tabs plus "Case" and "Doc Comments". */
        @Override
        protected void initTabs(CodeStyleSettings settings) {
            super.initTabs(settings);
            addTab(new AhkCaseCodeStylePanel(getCurrentSettings(), settings));
            addTab(new AhkDocCommentCodeStylePanel(getCurrentSettings(), settings));
        }
    }
}
