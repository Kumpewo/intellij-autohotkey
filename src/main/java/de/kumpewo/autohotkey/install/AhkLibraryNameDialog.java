package de.kumpewo.autohotkey.install;

import com.intellij.openapi.project.Project;
import com.intellij.openapi.ui.DialogWrapper;
import com.intellij.openapi.ui.ValidationInfo;
import com.intellij.ui.components.JBLabel;
import com.intellij.ui.components.JBTextField;
import com.intellij.util.ui.FormBuilder;
import com.intellij.util.ui.UIUtil;
import de.kumpewo.autohotkey.AhkBundle;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import javax.swing.Action;
import javax.swing.JComponent;
import java.awt.event.ActionEvent;
import java.util.List;

/**
 * Asks for a library name ({@link AhkLibraryLayout#parseName}, e.g. {@code v2\Gui}). With a warning (a new library
 * file in Lib-src mode) the main button creates a library project instead, and a second button creates the file
 * anyway ({@link #createsFile()}).
 */
final class AhkLibraryNameDialog extends DialogWrapper {

    private final JBTextField name = new JBTextField(30);
    private final @Nullable String warning;
    private final @NotNull String hint;
    private boolean file;

    /**
     * @param project the project (or {@code null} on the welcome screen)
     * @param title   title of the dialog
     * @param hint    text below the field (where the file or project is created)
     * @param ok      text of the main button
     * @param warning warning above the field; with it the dialog also has the button "Create File Anyway"
     */
    AhkLibraryNameDialog(@Nullable Project project, @NotNull String title, @NotNull String hint, @NotNull String ok,
                         @Nullable String warning) {
        super(project);
        this.warning = warning;
        this.hint = hint;
        setTitle(title);
        setOKButtonText(ok);
        init();
    }

    /** The field, the hint and the warning, if any. */
    @Override
    protected @Nullable JComponent createCenterPanel() {
        JBLabel hintLabel = new JBLabel(hint);
        hintLabel.setComponentStyle(UIUtil.ComponentStyle.SMALL);
        hintLabel.setFontColor(UIUtil.FontColor.BRIGHTER);
        FormBuilder form = FormBuilder.createFormBuilder();
        if (warning != null) form.addComponent(new JBLabel(warning, UIUtil.getBalloonWarningIcon(), JBLabel.LEADING));
        return form.addLabeledComponent(AhkBundle.message("library.name.label"), name)
                .addComponentToRightColumn(hintLabel).getPanel();
    }

    /** The field gets the focus. */
    @Override
    public @Nullable JComponent getPreferredFocusedComponent() {
        return name;
    }

    /** With a warning: "Create File Anyway" next to the main button. */
    @Override
    protected Action @NotNull [] createActions() {
        if (warning == null) return super.createActions();
        Action anyway = new DialogWrapperAction(AhkBundle.message("library.file.anyway")) {
            @Override
            protected void doAction(ActionEvent e) {
                if (doValidate() != null) return;
                file = true;
                close(OK_EXIT_CODE);
            }
        };
        return new Action[]{getOKAction(), anyway, getCancelAction()};
    }

    /** The name must be valid. */
    @Override
    protected @Nullable ValidationInfo doValidate() {
        return segments() == null ? new ValidationInfo(AhkBundle.message("library.name.invalid"), name) : null;
    }

    /** The segments of the name entered, {@code null} if invalid. */
    @Nullable List<String> segments() {
        return AhkLibraryLayout.parseName(name.getText());
    }

    /** Was "Create File Anyway" chosen (only with a warning)? */
    boolean createsFile() {
        return file || warning == null;
    }
}
