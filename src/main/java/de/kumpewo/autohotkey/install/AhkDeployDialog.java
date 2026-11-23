package de.kumpewo.autohotkey.install;

import com.intellij.icons.AllIcons;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.ui.DialogWrapper;
import com.intellij.openapi.util.text.StringUtil;
import com.intellij.ui.components.ActionLink;
import com.intellij.ui.components.JBLabel;
import com.intellij.util.ui.FormBuilder;
import com.intellij.util.ui.JBFont;
import com.intellij.util.ui.JBUI;
import com.intellij.util.ui.UIUtil;
import de.kumpewo.autohotkey.AhkBundle;
import de.kumpewo.autohotkey.documentation.AhkExternalHelp;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import javax.swing.Action;
import javax.swing.JComponent;
import javax.swing.JPanel;
import java.awt.BorderLayout;
import java.awt.FlowLayout;
import java.util.List;

/**
 * Confirmation of a deployment (always shown, OK/Cancel): the {@code Message} of the
 * {@code settings.ahk-v2-plugin.ini}, the overview ({@code GenerateOverview}: compile step and the copy sections without
 * comments, in a monospaced font), or without both one sentence "Deploy … to …?"; a prominent note with a warning
 * icon if {@code RequiresAdmin} is on; warnings (red); hints (gray, only noticed); the hint about uncommitted changes with a link "More..." to the
 * manual. For a file with the names of version 2.1 the button "Update Settings" closes the dialog and asks
 * {@link AhkDeployer} to rename them ({@link #updateRequested}).
 */
final class AhkDeployDialog extends DialogWrapper {

    /** Section of the manual page that explains the hint about uncommitted changes. */
    static final String UNCOMMITTED_HELP = "/extra/installer.htm#Uncommitted";

    private final Project project;
    private final AhkDeployer.Prepared prepared;
    /** Name of the tool in the question without overview (the project name). */
    private final String tool;
    /** "Update Settings" was pressed. */
    private boolean update;

    AhkDeployDialog(@NotNull Project project, @NotNull String title, @NotNull String tool,
                     @NotNull AhkDeployer.Prepared prepared) {
        super(project);
        this.project = project;
        this.prepared = prepared;
        this.tool = tool;
        setTitle(title);
        setOKButtonText(AhkBundle.message("deploy.dialog.ok"));
        init();
    }

    /** True if the dialog was closed with "Update Settings" (not deployed). */
    boolean updateRequested() {
        return update;
    }

    /** "Update Settings" on the left, only for a file with the names of version 2.1. */
    @Override
    protected @NotNull Action @NotNull [] createLeftSideActions() {
        if (!prepared.config().legacy()) return new Action[0];
        return new Action[]{new DialogWrapperAction(AhkBundle.message("deploy.dialog.update")) {
            @Override
            protected void doAction(java.awt.event.ActionEvent e) {
                update = true;
                close(CANCEL_EXIT_CODE);
            }
        }};
    }

    @Override
    protected @Nullable JComponent createCenterPanel() {
        FormBuilder form = FormBuilder.createFormBuilder();
        if (prepared.config().requiresAdmin()) {
            JBLabel admin = new JBLabel(AhkBundle.message("deploy.dialog.admin"), AllIcons.General.Warning,
                    JBLabel.LEFT);
            admin.setFont(JBFont.label().asBold());
            form.addComponent(admin).addVerticalGap(JBUI.scale(6));
        }
        String text = prepared.plan().message();
        if (text != null) form.addComponent(label(text));
        if (prepared.config().generateOverview()) {
            if (prepared.plan().destination() != null) {
                form.addComponent(label(AhkBundle.message("deploy.dialog.destination", prepared.plan().destination())));
            }
            form.addComponent(label(AhkBundle.message("deploy.dialog.overview")));
            form.addComponent(code(prepared.overview()));
        } else if (text == null) {
            form.addComponent(label(prepared.plan().destination() == null
                    ? AhkBundle.message("deploy.dialog.question.targets", tool, prepared.config().legacy()
                    ? AhkDeployConfig.LEGACY_FILE_NAME : AhkDeployConfig.FILE_NAME)
                    : AhkBundle.message("deploy.dialog.question", tool, prepared.plan().destination())));
        }
        for (String warning : prepared.warnings()) {
            JBLabel label = indented(warning);
            label.setForeground(UIUtil.getErrorForeground());
            form.addComponent(label);
        }
        for (String hint : prepared.hints()) {
            JBLabel label = indented(hint);
            label.setForeground(UIUtil.getContextHelpForeground());
            form.addComponent(label);
        }
        if (prepared.uncommitted()) {
            JPanel row = new JPanel(new FlowLayout(FlowLayout.LEFT, 0, 0));
            JBLabel label = new JBLabel(AhkBundle.message("deploy.dialog.uncommitted") + " ");
            label.setForeground(UIUtil.getErrorForeground());
            row.add(label);
            row.add(new ActionLink(AhkBundle.message("deploy.dialog.more"),
                    (java.awt.event.ActionListener) e -> AhkExternalHelp.openExtra(project, UNCOMMITTED_HELP)));
            form.addComponent(row);
        }
        JPanel panel = new JPanel(new BorderLayout());
        panel.add(form.getPanel(), BorderLayout.NORTH);
        panel.setBorder(JBUI.Borders.empty(4));
        return panel;
    }

    /** Label with line breaks. */
    private static JBLabel label(String text) {
        return new JBLabel("<html>" + StringUtil.escapeXmlEntities(text).replace("\n", "<br>") + "</html>");
    }

    /**
     * Label for a warning or hint: line breaks, continuation lines indented, so that a message of several lines
     * stands apart from the next one.
     */
    private static JBLabel indented(String text) {
        return new JBLabel("<html>" + StringUtil.escapeXmlEntities(text)
                .replace("\n", "<br>&nbsp;&nbsp;&nbsp;&nbsp;") + "</html>");
    }

    /** Lines in the editor font, indented (section headers not). */
    private static JBLabel code(List<String> lines) {
        StringBuilder html = new StringBuilder("<html>");
        for (String line : lines) {
            if (!line.startsWith("[")) html.append("&nbsp;&nbsp;");
            html.append(StringUtil.escapeXmlEntities(line).replace(" ", "&nbsp;")).append("<br>");
        }
        JBLabel label = new JBLabel(html.append("</html>").toString());
        label.setFont(JBUI.Fonts.create(java.awt.Font.MONOSPACED, label.getFont().getSize()));
        return label;
    }
}
