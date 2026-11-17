package de.kumpewo.autohotkey.documentation;

import com.intellij.openapi.actionSystem.ActionUpdateThread;
import com.intellij.openapi.actionSystem.AnActionEvent;
import com.intellij.openapi.project.DumbAwareAction;
import de.kumpewo.autohotkey.parser.AhkLanguageLevel;
import org.jetbrains.annotations.NotNull;

/**
 * Menu Help &gt; "AutoHotkey v2.0 Help" (this class) and "AutoHotkey v2.1 Help" ({@link V21}): opens the help of
 * AutoHotkey of that version ({@link AhkExternalHelp#openDocs(com.intellij.openapi.project.Project, AhkLanguageLevel)}).
 */
public class AhkHelpAction extends DumbAwareAction {

    private final AhkLanguageLevel level;

    /** The entry for AutoHotkey v2.0. */
    public AhkHelpAction() {
        this(AhkLanguageLevel.V2_0);
    }

    /** The entry for the given language level. */
    protected AhkHelpAction(@NotNull AhkLanguageLevel level) {
        this.level = level;
    }

    /** Opens the AutoHotkey help. */
    @Override
    public void actionPerformed(@NotNull AnActionEvent e) {
        AhkExternalHelp.openDocs(e.getProject(), level);
    }

    /** Always available, also without a project. */
    @Override
    public @NotNull ActionUpdateThread getActionUpdateThread() {
        return ActionUpdateThread.BGT;
    }

    /** Menu Help &gt; "AutoHotkey v2.1 Help". */
    public static final class V21 extends AhkHelpAction {

        /** The entry for AutoHotkey v2.1. */
        public V21() {
            super(AhkLanguageLevel.V2_1);
        }
    }
}
