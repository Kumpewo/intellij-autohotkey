package de.kumpewo.autohotkey.settings;

import de.kumpewo.autohotkey.AhkBundle;
import com.intellij.openapi.fileChooser.FileChooserDescriptorFactory;
import com.intellij.openapi.options.Configurable;
import com.intellij.openapi.ui.ComboBox;
import com.intellij.openapi.ui.TextFieldWithBrowseButton;
import com.intellij.openapi.util.NlsContexts;
import com.intellij.ui.SimpleListCellRenderer;
import com.intellij.ui.components.JBLabel;
import com.intellij.ui.components.JBTextField;
import com.intellij.util.ui.FormBuilder;
import com.intellij.util.ui.UIUtil;
import org.jetbrains.annotations.Nullable;

import javax.swing.*;
import java.nio.file.Path;

/**
 * Settings page "Languages &amp; Frameworks > AutoHotkey": path to the AutoHotkey program, command line of the
 * external help (Shift+F1), automatic validation with AutoHotkey, path to the compiler Ahk2Exe and the compression
 * of the compiled executable; below them a link to the user manual of the plugin.
 */
public final class AhkSettingsConfigurable implements Configurable {

    private TextFieldWithBrowseButton path;
    private JBTextField helpCommand;
    private ComboBox<AhkSettings.ValidateTrigger> validateTrigger;
    private TextFieldWithBrowseButton ahk2ExePath;
    private ComboBox<AhkSettings.Compression> compression;
    private JPanel panel;

    /** Name of the page in the settings tree. */
    @Override
    public @NlsContexts.ConfigurableName String getDisplayName() {
        return "AutoHotkey";
    }

    /** Builds the form; empty fields show the automatically detected values as placeholder text. */
    @Override
    public @Nullable JComponent createComponent() {
        JBTextField text = new JBTextField();
        Path detected = AhkSettings.detectAutoHotkey();
        text.getEmptyText().setText(detected == null
                ? AhkBundle.message("settings.autohotkey.not.found")
                : AhkBundle.message("settings.automatic", detected.toString()));
        path = new TextFieldWithBrowseButton(text);
        path.addBrowseFolderListener(null, FileChooserDescriptorFactory.createSingleFileDescriptor("exe")
                .withTitle(AhkBundle.message("settings.autohotkey.choose")));
        JBLabel hint = new JBLabel(AhkBundle.message("settings.autohotkey.hint", AhkSettings.LAUNCHER_EXE,
                AhkSettings.LAUNCHER_SCRIPT));
        hint.setComponentStyle(UIUtil.ComponentStyle.SMALL);
        hint.setFontColor(UIUtil.FontColor.BRIGHTER);
        helpCommand = new JBTextField();
        Path chm = de.kumpewo.autohotkey.documentation.AhkExternalHelp.findChm();
        helpCommand.getEmptyText().setText(chm == null
                ? AhkBundle.message("settings.help.chm.not.found", de.kumpewo.autohotkey.documentation.AhkExternalHelp.CHM)
                : AhkBundle.message("settings.automatic", "hh.exe \"mk:@MSITStore:" + chm + "::%s\""));
        JBLabel helpHint = new JBLabel(AhkBundle.message("settings.help.hint"));
        helpHint.setComponentStyle(UIUtil.ComponentStyle.SMALL);
        helpHint.setFontColor(UIUtil.FontColor.BRIGHTER);
        validateTrigger = new ComboBox<>(AhkSettings.ValidateTrigger.values());
        validateTrigger.setRenderer(SimpleListCellRenderer.create("",
                trigger -> AhkBundle.message("settings.validate." + trigger.name().toLowerCase(java.util.Locale.ROOT))));
        JBLabel validateHint = new JBLabel(AhkBundle.message("settings.validate.hint"));
        validateHint.setComponentStyle(UIUtil.ComponentStyle.SMALL);
        validateHint.setFontColor(UIUtil.FontColor.BRIGHTER);
        JBTextField ahk2ExeText = new JBTextField();
        Path detectedAhk2Exe = AhkSettings.detectAhk2Exe();
        ahk2ExeText.getEmptyText().setText(detectedAhk2Exe == null
                ? AhkBundle.message("settings.ahk2exe.not.found")
                : AhkBundle.message("settings.automatic", detectedAhk2Exe.toString()));
        ahk2ExePath = new TextFieldWithBrowseButton(ahk2ExeText);
        ahk2ExePath.addBrowseFolderListener(null, FileChooserDescriptorFactory.createSingleFileDescriptor("exe")
                .withTitle(AhkBundle.message("settings.ahk2exe.choose")));
        compression = new ComboBox<>(AhkSettings.Compression.values());
        compression.setRenderer(SimpleListCellRenderer.create("",
                value -> AhkBundle.message("settings.compression." + value.name().toLowerCase(java.util.Locale.ROOT))));
        JBLabel compileHint = new JBLabel(AhkBundle.message("settings.compile.hint"));
        compileHint.setComponentStyle(UIUtil.ComponentStyle.SMALL);
        compileHint.setFontColor(UIUtil.FontColor.BRIGHTER);
        panel = FormBuilder.createFormBuilder()
                .addLabeledComponent(AhkBundle.message("settings.autohotkey.path"), path)
                .addComponentToRightColumn(hint)
                .addLabeledComponent(AhkBundle.message("settings.help.command"), helpCommand)
                .addComponentToRightColumn(helpHint)
                .addLabeledComponent(AhkBundle.message("settings.validate"), validateTrigger)
                .addComponentToRightColumn(validateHint)
                .addLabeledComponent(AhkBundle.message("settings.ahk2exe.path"), ahk2ExePath)
                .addLabeledComponent(AhkBundle.message("settings.compression"), compression)
                .addComponentToRightColumn(compileHint)
                .addVerticalGap(10)
                .addComponent(new com.intellij.ui.components.ActionLink(AhkBundle.message("settings.manual"),
                        (java.awt.event.ActionListener) e -> de.kumpewo.autohotkey.documentation.AhkManual.open(null)))
                .addComponentFillVertically(new JPanel(), 0)
                .getPanel();
        return panel;
    }

    /** True if a field differs from the stored settings. */
    @Override
    public boolean isModified() {
        AhkSettings settings = AhkSettings.getInstance();
        return !path.getText().trim().equals(settings.getAutoHotkeyPath())
                || !helpCommand.getText().trim().equals(settings.getExternalHelpCommand())
                || validateTrigger.getSelectedItem() != settings.getValidateTrigger()
                || !ahk2ExePath.getText().trim().equals(settings.getAhk2ExePath())
                || compression.getSelectedItem() != settings.getCompression();
    }

    /** Stores the fields; a new program path may change the library roots. */
    @Override
    public void apply() {
        // other program = possibly another standard Lib folder: update the library "AutoHotkey Lib"
        String text = path.getText();
        AhkSettings.getInstance().setExternalHelpCommand(helpCommand.getText());
        if (validateTrigger.getSelectedItem() instanceof AhkSettings.ValidateTrigger trigger) {
            AhkSettings.getInstance().setValidateTrigger(trigger);
        }
        AhkSettings.getInstance().setAhk2ExePath(ahk2ExePath.getText());
        if (compression.getSelectedItem() instanceof AhkSettings.Compression value) {
            AhkSettings.getInstance().setCompression(value);
        }
        de.kumpewo.autohotkey.include.AhkLibraryRootsProvider.changeRoots(
                () -> AhkSettings.getInstance().setAutoHotkeyPath(text));
    }

    /** Fills the fields from the stored settings. */
    @Override
    public void reset() {
        path.setText(AhkSettings.getInstance().getAutoHotkeyPath());
        helpCommand.setText(AhkSettings.getInstance().getExternalHelpCommand());
        validateTrigger.setSelectedItem(AhkSettings.getInstance().getValidateTrigger());
        ahk2ExePath.setText(AhkSettings.getInstance().getAhk2ExePath());
        compression.setSelectedItem(AhkSettings.getInstance().getCompression());
    }

    /** Releases the UI components. */
    @Override
    public void disposeUIResources() {
        path = null;
        helpCommand = null;
        validateTrigger = null;
        ahk2ExePath = null;
        compression = null;
        panel = null;
    }
}
