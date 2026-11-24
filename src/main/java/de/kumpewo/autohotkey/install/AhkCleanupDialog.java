package de.kumpewo.autohotkey.install;

import com.intellij.icons.AllIcons;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.ui.DialogWrapper;
import com.intellij.openapi.util.text.StringUtil;
import com.intellij.ui.components.JBLabel;
import com.intellij.ui.components.JBScrollPane;
import com.intellij.util.ui.JBFont;
import com.intellij.util.ui.JBUI;
import com.intellij.util.ui.UIUtil;
import de.kumpewo.autohotkey.AhkBundle;
import org.jetbrains.annotations.NotNull;

import javax.swing.Action;
import javax.swing.JComponent;
import javax.swing.JPanel;
import java.awt.BorderLayout;
import java.awt.Dimension;
import java.util.List;

/**
 * The second confirmation of "Deploy to Local Target..." when {@code [Cleanup]} finds files to remove: a clear warning
 * that this is risky and part of the deployment, then every file with its absolute path, grouped by the line of
 * {@code [Cleanup]}. Buttons "Remove and Deploy", "Deploy Without Removing" and "Cancel" (the default, so Enter
 * removes nothing).
 */
final class AhkCleanupDialog extends DialogWrapper {

    /** What the user chose. */
    enum Choice { REMOVE, SKIP, CANCEL }

    /** Exit code of "Deploy Without Removing". */
    private static final int SKIP_EXIT_CODE = NEXT_USER_EXIT_CODE;

    private final List<AhkDeployPlan.Removal> removals;

    AhkCleanupDialog(@NotNull Project project, @NotNull String title, @NotNull List<AhkDeployPlan.Removal> removals) {
        super(project);
        this.removals = removals;
        setTitle(title);
        setOKButtonText(AhkBundle.message("deploy.cleanup.remove"));
        getOKAction().putValue(DEFAULT_ACTION, null);
        getCancelAction().putValue(DEFAULT_ACTION, Boolean.TRUE);
        init();
    }

    /** The size of the dialog is remembered. */
    @Override
    protected @NotNull String getDimensionServiceKey() {
        return AhkDialogs.sizeKey("Cleanup");
    }

    /** Remove and deploy, deploy without removing, cancel. */
    @Override
    protected Action @NotNull [] createActions() {
        Action skip = new DialogWrapperExitAction(AhkBundle.message("deploy.cleanup.skip"), SKIP_EXIT_CODE);
        return new Action[]{getOKAction(), skip, getCancelAction()};
    }

    /** "Cancel" has the focus. */
    @Override
    public JComponent getPreferredFocusedComponent() {
        return getButton(getCancelAction());
    }

    /** The choice after the dialog was closed. */
    @NotNull Choice choice() {
        return switch (getExitCode()) {
            case OK_EXIT_CODE -> Choice.REMOVE;
            case SKIP_EXIT_CODE -> Choice.SKIP;
            default -> Choice.CANCEL;
        };
    }

    @Override
    protected JComponent createCenterPanel() {
        JBLabel warning = new JBLabel("<html>" + StringUtil.escapeXmlEntities(
                AhkBundle.message("deploy.cleanup.warning", removals.size())).replace("\n", "<br>") + "</html>",
                AllIcons.General.Warning, JBLabel.LEFT);
        warning.setFont(JBFont.label().asBold());
        warning.setForeground(UIUtil.getErrorForeground());
        StringBuilder html = new StringBuilder("<html>");
        int line = -1;
        for (AhkDeployPlan.Removal removal : removals) {
            if (removal.line() != line) {
                line = removal.line();
                html.append(StringUtil.escapeXmlEntities(AhkBundle.message("deploy.cleanup.line", line,
                        removal.entry())).replace(" ", "&nbsp;")).append("<br>");
            }
            html.append("&nbsp;&nbsp;").append(StringUtil.escapeXmlEntities(removal.file().toString())
                    .replace(" ", "&nbsp;")).append("<br>");
        }
        JBLabel list = new JBLabel(html.append("</html>").toString());
        list.setFont(JBUI.Fonts.create(java.awt.Font.MONOSPACED, list.getFont().getSize()));
        list.setVerticalAlignment(JBLabel.TOP);
        list.setBorder(JBUI.Borders.empty(4));
        JBScrollPane scroll = new JBScrollPane(list);
        Dimension size = list.getPreferredSize();
        scroll.setPreferredSize(new Dimension(Math.min(size.width + JBUI.scale(30), JBUI.scale(900)),
                Math.min(size.height + JBUI.scale(10), JBUI.scale(350))));
        JPanel panel = new JPanel(new BorderLayout(0, JBUI.scale(8)));
        panel.add(warning, BorderLayout.NORTH);
        panel.add(scroll, BorderLayout.CENTER);
        panel.setBorder(JBUI.Borders.empty(4));
        return panel;
    }
}
