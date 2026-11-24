package de.kumpewo.autohotkey.install;

import com.intellij.openapi.actionSystem.CommonShortcuts;
import com.intellij.openapi.project.DumbAwareAction;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.ui.DialogWrapper;
import com.intellij.openapi.ui.ValidationInfo;
import com.intellij.ui.DocumentAdapter;
import com.intellij.ui.JBColor;
import com.intellij.ui.components.JBCheckBox;
import com.intellij.ui.components.JBLabel;
import com.intellij.ui.components.JBRadioButton;
import com.intellij.ui.components.JBScrollPane;
import com.intellij.ui.components.JBTextArea;
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
import java.awt.Color;
import java.awt.FlowLayout;
import java.awt.KeyboardFocusManager;
import java.util.ArrayList;
import java.util.List;

/**
 * Dialog of "Set Version...": the current version and the scripts that get the new one, the next patch, minor or
 * major version (without any version yet: 0.0.1, 0.1.0, 1.0.0) or a free one (the field shows the chosen version,
 * typing there selects "Other"), one check box "Commit the project and create tag v…" (disabled without Git and while
 * files of the project are neither versioned nor ignored, with a hint), the editable message of several lines, and the
 * uncommitted changes that are committed with the version. Lists are shortened ({@link AhkDialogs#list}). Validates the
 * version and that the tag does not exist yet.
 */
final class AhkSetVersionDialog extends DialogWrapper {

    /** Height of the message in lines. */
    private static final int MESSAGE_ROWS = 5;
    /** Color of hints. */
    private static final Color HINT = JBColor.lazy(UIUtil::getContextHelpForeground);
    /** Color of warnings. */
    private static final Color WARNING = JBColor.lazy(UIUtil::getErrorForeground);

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
    private JBTextArea message;
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
        List<String> changedIn = new ArrayList<>();
        List<String> added = new ArrayList<>();
        for (AhkSetVersion.Script script : prepared.scripts()) {
            if (script.line() == null) added.add(script.name());
            else changedIn.add(script.name() + " (" + (script.line().line() + 1) + ")");
        }
        if (!changedIn.isEmpty()) addList(form, "version.dialog.scripts", changedIn, HINT);
        if (!added.isEmpty()) addList(form, "version.dialog.added", added, WARNING);
        List<String> different = prepared.differentVersions();
        if (!different.isEmpty()) addList(form, "version.dialog.different", different, WARNING);
        for (String warning : AhkDialogs.limit(prepared.warnings(), AhkDialogs.MAX_ITEMS)) {
            form.addComponent(AhkDialogs.text(warning, WARNING));
        }
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
            form.addComponent(AhkDialogs.text(AhkBundle.message("version.dialog.no.git"), HINT));
        } else if (!prepared.untracked().isEmpty()) {
            addList(form, "version.dialog.untracked", prepared.untracked(), HINT);
            form.addComponent(AhkDialogs.text(AhkBundle.message("version.dialog.untracked.hint"), HINT));
        } else if (!prepared.changed().isEmpty()) {
            addList(form, "version.dialog.changed", prepared.changed(), WARNING);
        }
        proposed = prepared.message(version());
        message = new JBTextArea(proposed, MESSAGE_ROWS, 70);
        message.setLineWrap(true);
        message.setWrapStyleWord(true);
        // Tab leaves the field, Enter starts a new line, Ctrl+Enter is "Set Version"
        message.setFocusTraversalKeys(KeyboardFocusManager.FORWARD_TRAVERSAL_KEYS, null);
        message.setFocusTraversalKeys(KeyboardFocusManager.BACKWARD_TRAVERSAL_KEYS, null);
        DumbAwareAction.create(e -> clickDefaultButton())
                .registerCustomShortcutSet(CommonShortcuts.getCtrlEnter(), message, getDisposable());
        JBScrollPane messageScroll = new JBScrollPane(message);
        JBLabel messageLabel = new JBLabel(AhkBundle.message("version.dialog.message"));
        messageLabel.setLabelFor(message);
        form.addVerticalGap(JBUI.scale(6)).addComponent(messageLabel);

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
        JPanel panel = new JPanel(new BorderLayout(0, JBUI.scale(4)));
        panel.add(form.getPanel(), BorderLayout.NORTH);
        // a larger window gives the message more lines
        panel.add(messageScroll, BorderLayout.CENTER);
        return AhkDialogs.scrollable(panel);
    }

    /** Adds a heading from the bundle with its entries. */
    private static void addList(FormBuilder form, String key, List<String> items, Color color) {
        form.addComponent(AhkDialogs.list(AhkBundle.message(key), items, color));
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

    /** The size of the dialog is remembered. */
    @Override
    protected @NotNull String getDimensionServiceKey() {
        return AhkDialogs.sizeKey("SetVersion");
    }

    /** Focus on the version field if no version can be proposed. */
    @Override
    public @Nullable JComponent getPreferredFocusedComponent() {
        return patch == null ? custom : patchButton;
    }
}
