package de.kumpewo.autohotkey.formatter;

import java.util.List;

/** Test access to the package-private tab "Doc Comments" (Z.23). */
public final class AhkDocCommentCodeStylePanelAccess {

    private AhkDocCommentCodeStylePanelAccess() {
    }

    /** The field names of all switches of the tab. */
    public static List<String> fields() {
        return AhkDocCommentCodeStylePanel.fields();
    }
}
