package de.kumpewo.autohotkey.level;

import com.intellij.openapi.project.Project;
import com.intellij.openapi.util.NlsContexts;
import com.intellij.openapi.wm.StatusBar;
import com.intellij.openapi.wm.StatusBarWidget;
import com.intellij.openapi.wm.StatusBarWidgetFactory;
import de.kumpewo.autohotkey.AhkBundle;
import org.jetbrains.annotations.NotNull;

/**
 * Factory of the status bar widget with the language level of the current AutoHotkey file ({@link AhkLevelWidget}).
 * It can be switched off like every widget (right-click on the status bar).
 */
public final class AhkLevelWidgetFactory implements StatusBarWidgetFactory {

    /** ID of the widget (also in {@code plugin.xml}). */
    public static final String ID = "AutoHotkey.LanguageLevel";

    /** ID of the widget. */
    @Override
    public @NotNull String getId() {
        return ID;
    }

    /** Name in the status bar menu. */
    @Override
    public @NlsContexts.ConfigurableName @NotNull String getDisplayName() {
        return AhkBundle.message("level.status.name");
    }

    /** Available in every project; the widget stays empty for files that are not AutoHotkey files. */
    @Override
    public boolean isAvailable(@NotNull Project project) {
        return true;
    }

    /** Creates the widget. */
    @Override
    public @NotNull StatusBarWidget createWidget(@NotNull Project project) {
        return new AhkLevelWidget(project);
    }

    /** Can be shown in every status bar. */
    @Override
    public boolean canBeEnabledOn(@NotNull StatusBar statusBar) {
        return true;
    }
}
