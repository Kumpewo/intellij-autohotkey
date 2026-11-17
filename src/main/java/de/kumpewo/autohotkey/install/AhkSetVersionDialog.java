package de.kumpewo.autohotkey.install;

import com.intellij.openapi.project.Project;
import com.intellij.openapi.ui.DialogWrapper;
import com.intellij.openapi.ui.ValidationInfo;
import com.intellij.ui.DocumentAdapter;
import com.intellij.ui.components.JBCheckBox;
import com.intellij.ui.components.JBLabel;
import com.intellij.ui.components.JBRadioButton;
import com.intellij.ui.components.JBTextField;
import com.intellij.util.ui.FormBuilder;
import com.intellij.util.ui.JBUI;
import com.intellij.util.ui.UIUtil;
import de.kumpewo.autohotkey.AhkBundle;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import javax.swing.ButtonGroup;
import javax.swing.JComponent;
import javax.swing.JPanel;
import javax.swing.event.DocumentEvent;
import java.awt.BorderLayout;
import java.awt.FlowLayout;

/**
 * Dialog of "Set Version...": the current version, the next patch, minor or major version or a free one, check boxes
 * for commit and tag (disabled without Git; the tag also without {@code TagPrefix} and without commit, because the
 * tag must point to the commit with the new version), the editable message, and warnings about other uncommitted
 * changes. Validates the version and that the tag does not exist yet.
 */
final class AhkSetVersionDialog extends DialogWrapper {

    private final AhkSetVersion.Prepared prepared;
    private final String patch;
    private final String minor;
    private final String major;
    private JBRadioButton patchButton;
    private JBRadioButton minorButton;
    private JBRadioButton majorButton;
    private JBRadioButton customButton;
    private JBTextField custom;
    private JBCheckBox commit;
    private JBCheckBox tag;
    private JBTextField message;
    /** The last proposed message; while the field shows it, it follows the version. */
    private String proposed;

    AhkSetVersionDialog(@NotNull Project project, @NotNull String title, @NotNull AhkSetVersion.Prepared prepared) {
        super(project);
        this.prepared = prepared;
        String current = prepared.current();
        patch = AhkVersionDirective.bump(current, AhkVersionDirective.Part.PATCH);
        minor = AhkVersionDirective.bump(current, AhkVersionDirective.Part.MINOR);
        major = AhkVersionDirective.bump(current, AhkVersionDirective.Part.MAJOR);
        setTitle(title);
        setOKButtonText(AhkBundle.message("version.dialog.ok"));
        init();
        update();
    }

    /** The choice of the user (after OK). */
    @NotNull AhkSetVersion.Choice choice() {
        String version = version();
        boolean doCommit = commit.isEnabled() && commit.isSelected();
        boolean doTag = doCommit && tag.isEnabled() && tag.isSelected() && prepared.tagPrefix() != null;
        String text = message.getText().strip();
        return new AhkSetVersion.Choice(version, doCommit,
                doTag ? AhkVersionDirective.tagName(prepared.tagPrefix(), version) : null,
                text.isEmpty() ? prepared.message(version) : text);
    }

    /** The selected version. */
    private @NotNull String version() {
        if (patchButton.isSelected()) return patch;
        if (minorButton.isSelected()) return minor;
        if (majorButton.isSelected()) return major;
        return custom.getText().strip();
    }

    @Override
    protected @Nullable JComponent createCenterPanel() {
        FormBuilder form = FormBuilder.createFormBuilder();
        form.addComponent(new JBLabel(AhkBundle.message("version.dialog.current", prepared.current(),
                prepared.fileName(), prepared.line() == null ? 0 : prepared.line().line() + 1)));
        ButtonGroup group = new ButtonGroup();
        patchButton = radio(group, "version.dialog.patch", patch);
        minorButton = radio(group, "version.dialog.minor", minor);
        majorButton = radio(group, "version.dialog.major", major);
        customButton = new JBRadioButton(AhkBundle.message("version.dialog.custom"));
        group.add(customButton);
        custom = new JBTextField(patch == null ? prepared.current() : patch, 20);
        JPanel customRow = new JPanel(new FlowLayout(FlowLayout.LEFT, 0, 0));
        customRow.add(customButton);
        customRow.add(custom);
        form.addComponent(patchButton).addComponent(minorButton).addComponent(majorButton).addComponent(customRow);
        if (patch != null) {
            patchButton.setSelected(true);
        } else {
            customButton.setSelected(true);
            patchButton.setEnabled(false);
            minorButton.setEnabled(false);
            majorButton.setEnabled(false);
        }
        commit = new JBCheckBox(AhkBundle.message("version.dialog.commit", prepared.fileName()), prepared.repository());
        commit.setEnabled(prepared.repository());
        tag = new JBCheckBox("", prepared.repository() && prepared.tagPrefix() != null);
        tag.setEnabled(prepared.repository() && prepared.tagPrefix() != null);
        form.addVerticalGap(JBUI.scale(6)).addComponent(commit).addComponent(tag);
        if (!prepared.repository()) {
            form.addComponent(hint(AhkBundle.message("version.dialog.no.git")));
        } else if (prepared.tagPrefix() == null) {
            form.addComponent(hint(AhkBundle.message("version.dialog.no.prefix", AhkInstallConfig.FILE_NAME)));
        }
        if (prepared.scriptChanged()) {
            form.addComponent(warning(AhkBundle.message("version.dialog.script.changed", prepared.fileName())));
        }
        if (prepared.otherChanges()) form.addComponent(warning(AhkBundle.message("version.dialog.other.changes")));
        proposed = prepared.message(version());
        message = new JBTextField(proposed, 40);
        form.addLabeledComponent(AhkBundle.message("version.dialog.message"), message);

        for (JBRadioButton button : new JBRadioButton[]{patchButton, minorButton, majorButton, customButton}) {
            button.addActionListener(e -> update());
        }
        commit.addActionListener(e -> update());
        tag.addActionListener(e -> update());
        custom.getDocument().addDocumentListener(new DocumentAdapter() {
            @Override
            protected void textChanged(@NotNull DocumentEvent e) {
                if (!customButton.isSelected()) customButton.setSelected(true);
                update();
            }
        });
        JPanel panel = new JPanel(new BorderLayout());
        panel.add(form.getPanel(), BorderLayout.NORTH);
        panel.setBorder(JBUI.Borders.empty(4));
        return panel;
    }

    /** Radio button "Patch: 1.2.4" (without a version: only the name, disabled). */
    private static JBRadioButton radio(ButtonGroup group, String key, @Nullable String version) {
        JBRadioButton button = new JBRadioButton(AhkBundle.message(key, version == null ? "-" : version));
        group.add(button);
        return button;
    }

    /** Updates the tag check box, its text and the proposed message after a change. */
    private void update() {
        if (tag == null) return;
        String version = version();
        boolean canTag = prepared.repository() && prepared.tagPrefix() != null && commit.isSelected();
        tag.setEnabled(canTag);
        tag.setText(prepared.tagPrefix() == null ? AhkBundle.message("version.dialog.tag.none")
                : AhkBundle.message("version.dialog.tag", AhkVersionDirective.tagName(prepared.tagPrefix(),
                        version.isEmpty() ? "?" : version)));
        message.setEnabled(commit.isEnabled() && commit.isSelected());
        if (message.getText().equals(proposed)) {
            proposed = prepared.message(version);
            message.setText(proposed);
        }
    }

    @Override
    protected @Nullable ValidationInfo doValidate() {
        String version = version();
        if (!AhkVersionDirective.isValidVersion(version)) {
            return new ValidationInfo(AhkBundle.message("version.invalid"), customButton.isSelected() ? custom : null);
        }
        if (version.equals(prepared.current())) {
            return new ValidationInfo(AhkBundle.message("version.unchanged"), customButton.isSelected() ? custom : null);
        }
        AhkSetVersion.Choice choice = choice();
        if (choice.tag() != null && prepared.tagExists(choice.tag())) {
            return new ValidationInfo(AhkBundle.message("version.tag.exists", choice.tag()), tag);
        }
        return null;
    }

    /** Focus on the version field if no version can be proposed. */
    @Override
    public @Nullable JComponent getPreferredFocusedComponent() {
        return patch == null ? custom : patchButton;
    }

    private static JBLabel hint(String text) {
        JBLabel label = new JBLabel(text);
        label.setForeground(UIUtil.getContextHelpForeground());
        return label;
    }

    private static JBLabel warning(String text) {
        JBLabel label = new JBLabel(text);
        label.setForeground(UIUtil.getErrorForeground());
        return label;
    }
}
