package de.kumpewo.autohotkey.install;

import com.intellij.openapi.project.Project;
import com.intellij.openapi.ui.DialogWrapper;
import com.intellij.openapi.ui.ValidationInfo;
import com.intellij.openapi.util.text.StringUtil;
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
import java.util.ArrayList;
import java.util.List;

/**
 * Dialog of "Set Version...": the current version and the scripts that get the new one, the next patch, minor or
 * major version (without any version yet: 0.0.1, 0.1.0, 1.0.0) or a free one (the field shows the chosen version,
 * typing there selects "Other"), one check box "Commit the project and
 * create tag v…" (disabled without Git and while files of the project are neither versioned nor ignored, with a
 * hint), the editable message, and the uncommitted changes that are committed with the version. Validates the version and that the tag does not exist yet.
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
    private JBTextField message;
    /** The free field is being filled with the version of a button (then "Other" is not selected). */
    private boolean filling;
    /** The last proposed message; while the field shows it, it follows the version. */
    private String proposed;

    AhkSetVersionDialog(@NotNull Project project, @NotNull String title, @NotNull AhkSetVersion.Prepared prepared) {
        super(project);
        this.prepared = prepared;
        String current = prepared.current();
        patch = AhkVersionDirective.next(current, AhkVersionDirective.Part.PATCH);
        minor = AhkVersionDirective.next(current, AhkVersionDirective.Part.MINOR);
        major = AhkVersionDirective.next(current, AhkVersionDirective.Part.MAJOR);
        setTitle(title);
        setOKButtonText(AhkBundle.message("version.dialog.ok"));
        init();
        update();
    }

    /** The choice of the user (after OK). */
    @NotNull AhkSetVersion.Choice choice() {
        String version = version();
        boolean doCommit = commit.isEnabled() && commit.isSelected();
        String text = message.getText().strip();
        return new AhkSetVersion.Choice(version, doCommit, AhkVersionDirective.tagName(version),
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
        form.addComponent(new JBLabel(prepared.current().isEmpty() ? AhkBundle.message("version.dialog.current.none")
                : AhkBundle.message("version.dialog.current", prepared.current())));
        List<String> scripts = new ArrayList<>();
        for (AhkSetVersion.Script script : prepared.scripts()) {
            scripts.add(script.line() == null ? AhkBundle.message("version.dialog.new.line", script.name())
                    : script.name() + " (" + (script.line().line() + 1) + ")");
        }
        form.addComponent(hint(AhkBundle.message("version.dialog.scripts", String.join(", ", scripts))));
        for (String warning : prepared.warnings()) form.addComponent(warning(warning));
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
        commit = new JBCheckBox("", prepared.canCommit());
        commit.setEnabled(prepared.canCommit());
        form.addVerticalGap(JBUI.scale(6)).addComponent(commit);
        if (!prepared.repository()) {
            form.addComponent(hint(AhkBundle.message("version.dialog.no.git")));
        } else if (!prepared.untracked().isEmpty()) {
            form.addComponent(hint(AhkBundle.message("version.dialog.untracked", list(prepared.untracked()))));
        } else if (!prepared.changed().isEmpty()) {
            form.addComponent(warning(AhkBundle.message("version.dialog.changed", list(prepared.changed()))));
        }
        proposed = prepared.message(version());
        message = new JBTextField(proposed, 40);
        form.addLabeledComponent(AhkBundle.message("version.dialog.message"), message);

        for (JBRadioButton button : new JBRadioButton[]{patchButton, minorButton, majorButton}) {
            button.addActionListener(e -> {
                showInField(version());
                update();
            });
        }
        customButton.addActionListener(e -> update());
        commit.addActionListener(e -> update());
        custom.getDocument().addDocumentListener(new DocumentAdapter() {
            @Override
            protected void textChanged(@NotNull DocumentEvent e) {
                if (filling) return;
                if (!customButton.isSelected()) customButton.setSelected(true);
                update();
            }
        });
        JPanel panel = new JPanel(new BorderLayout());
        panel.add(form.getPanel(), BorderLayout.NORTH);
        panel.setBorder(JBUI.Borders.empty(4));
        return panel;
    }

    /**
     * Shows the version of the chosen patch, minor or major button in the free field, so that it can be edited from
     * there (typing selects "Other"); the change itself does not select "Other".
     */
    private void showInField(@NotNull String version) {
        filling = true;
        try {
            custom.setText(version);
        } finally {
            filling = false;
        }
    }

    /** Radio button "Patch: 1.2.4" (without a version: only the name, disabled). */
    private static JBRadioButton radio(ButtonGroup group, String key, @Nullable String version) {
        JBRadioButton button = new JBRadioButton(AhkBundle.message(key, version == null ? "-" : version));
        group.add(button);
        return button;
    }

    /** At most five entries of a list, then an ellipsis with the number of the others. */
    static @NotNull String list(@NotNull List<String> items) {
        if (items.size() <= 5) return String.join(", ", items);
        return String.join(", ", items.subList(0, 5)) + ", … (+" + (items.size() - 5) + ")";
    }

    /** Updates the text of the check box (the tag) and the proposed message after a change. */
    private void update() {
        if (commit == null || message == null) return;
        String version = version();
        commit.setText(AhkBundle.message("version.dialog.commit",
                AhkVersionDirective.tagName(version.isEmpty() ? "?" : version)));
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
        if (choice.commit() && prepared.tagExists(choice.tag())) {
            return new ValidationInfo(AhkBundle.message("version.tag.exists", choice.tag()), commit);
        }
        return null;
    }

    /** Focus on the version field if no version can be proposed. */
    @Override
    public @Nullable JComponent getPreferredFocusedComponent() {
        return patch == null ? custom : patchButton;
    }

    /** A gray hint; a line break in the text starts a new line. */
    private static JBLabel hint(String text) {
        JBLabel label = label(text);
        label.setForeground(UIUtil.getContextHelpForeground());
        return label;
    }

    /** A warning in the error color; a line break in the text starts a new line. */
    private static JBLabel warning(String text) {
        JBLabel label = label(text);
        label.setForeground(UIUtil.getErrorForeground());
        return label;
    }

    /** A label whose text may contain line breaks (then as HTML, with the text escaped). */
    static JBLabel label(String text) {
        if (!text.contains("\n")) return new JBLabel(text);
        String[] lines = text.split("\n", -1);
        for (int i = 0; i < lines.length; i++) lines[i] = StringUtil.escapeXmlEntities(lines[i]);
        return new JBLabel("<html>" + String.join("<br>", lines) + "</html>");
    }
}
