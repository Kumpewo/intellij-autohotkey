package de.kumpewo.autohotkey.run;

import de.kumpewo.autohotkey.AhkBundle;
import com.intellij.openapi.fileChooser.FileChooser;
import com.intellij.openapi.fileChooser.FileChooserDescriptor;
import com.intellij.openapi.fileChooser.FileChooserDescriptorFactory;
import com.intellij.openapi.fileEditor.FileEditorManager;
import com.intellij.openapi.project.ProjectUtil;
import com.intellij.openapi.vfs.LocalFileSystem;
import com.intellij.openapi.vfs.VirtualFile;
import org.jetbrains.annotations.Nullable;
import com.intellij.openapi.options.SettingsEditor;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.ui.TextFieldWithBrowseButton;
import com.intellij.openapi.util.io.FileUtil;
import com.intellij.ui.RawCommandLineEditor;
import com.intellij.openapi.ui.ComboBox;
import com.intellij.ui.SimpleListCellRenderer;
import com.intellij.ui.components.JBCheckBox;
import com.intellij.ui.components.JBLabel;
import com.intellij.ui.components.JBTextField;
import com.intellij.util.ui.UIUtil;
import com.intellij.util.ui.FormBuilder;
import de.kumpewo.autohotkey.AhkFileType;
import org.jetbrains.annotations.NotNull;

import javax.swing.*;

/**
 * Form of the run configuration: script, parameters, working directory, "compile with Ahk2Exe first" and where the
 * compiled executable runs.
 */
final class AhkRunConfigurationEditor extends SettingsEditor<AhkRunConfiguration> {

    private final TextFieldWithBrowseButton script = new TextFieldWithBrowseButton();
    private final RawCommandLineEditor parameters = new RawCommandLineEditor();
    private final TextFieldWithBrowseButton workingDirectory;
    private final JBCheckBox compileFirst = new JBCheckBox(AhkBundle.message("run.editor.compile"));
    private final ComboBox<AhkRunConfiguration.ExeConsole> exeConsole =
            new ComboBox<>(AhkRunConfiguration.ExeConsole.values());
    private final JPanel panel;

    AhkRunConfigurationEditor(@NotNull Project project) {
        FileChooserDescriptor scriptDescriptor = FileChooserDescriptorFactory
                .createSingleFileDescriptor(AhkFileType.INSTANCE).withTitle(AhkBundle.message("run.editor.choose.script"));
        script.addActionListener(e -> choose(project, script, scriptDescriptor,
                existing(script.getText()), editorDirectory(project), ProjectUtil.guessProjectDir(project)));
        JBTextField directoryText = new JBTextField();
        directoryText.getEmptyText().setText(AhkBundle.message("run.editor.directory.empty"));
        workingDirectory = new TextFieldWithBrowseButton(directoryText);
        FileChooserDescriptor directoryDescriptor = FileChooserDescriptorFactory
                .createSingleFolderDescriptor().withTitle(AhkBundle.message("run.editor.choose.directory"));
        workingDirectory.addActionListener(e -> choose(project, workingDirectory, directoryDescriptor,
                existing(workingDirectory.getText()), parentOf(existing(script.getText())),
                editorDirectory(project), ProjectUtil.guessProjectDir(project)));
        exeConsole.setRenderer(SimpleListCellRenderer.create("", value -> AhkBundle.message(
                "run.editor.console." + value.name().toLowerCase(java.util.Locale.ROOT))));
        compileFirst.addActionListener(e -> exeConsole.setEnabled(compileFirst.isSelected()));
        JBLabel compileHint = new JBLabel(AhkBundle.message("run.editor.compile.hint"));
        compileHint.setComponentStyle(UIUtil.ComponentStyle.SMALL);
        compileHint.setFontColor(UIUtil.FontColor.BRIGHTER);
        panel = FormBuilder.createFormBuilder()
                .addLabeledComponent(AhkBundle.message("run.editor.script"), script)
                .addLabeledComponent(AhkBundle.message("run.editor.parameters"), parameters)
                .addLabeledComponent(AhkBundle.message("run.editor.directory"), workingDirectory)
                .addComponent(compileFirst)
                .addLabeledComponent(AhkBundle.message("run.editor.console"), exeConsole)
                .addComponentToRightColumn(compileHint)
                .addComponentFillVertically(new JPanel(), 0)
                .getPanel();
    }

    /**
     * File chooser that starts at the first suitable starting point (configured path, directory of the open
     * editor tab, project directory). Without a starting point Windows offered the desktop.
     */
    private static void choose(Project project, TextFieldWithBrowseButton field, FileChooserDescriptor descriptor,
                               VirtualFile... candidates) {
        VirtualFile start = null;
        for (VirtualFile candidate : candidates) {
            if (candidate != null && candidate.isValid()) {
                start = candidate;
                break;
            }
        }
        FileChooser.chooseFile(descriptor, project, field, start,
                chosen -> field.setText(FileUtil.toSystemDependentName(chosen.getPath())));
    }

    /** File or directory for the configured path, {@code null} if empty or not existing. */
    private static @Nullable VirtualFile existing(String path) {
        String trimmed = path.trim();
        if (trimmed.isEmpty()) return null;
        return LocalFileSystem.getInstance().findFileByPath(FileUtil.toSystemIndependentName(trimmed));
    }

    /** Parent directory, or {@code null}. */
    private static @Nullable VirtualFile parentOf(@Nullable VirtualFile file) {
        return file == null ? null : file.getParent();
    }

    /** Directory of the file in the active editor tab. */
    private static @Nullable VirtualFile editorDirectory(Project project) {
        VirtualFile[] selected = FileEditorManager.getInstance(project).getSelectedFiles();
        return selected.length == 0 ? null : selected[0].getParent();
    }

    /** Fills the form from the configuration. */
    @Override
    protected void resetEditorFrom(@NotNull AhkRunConfiguration configuration) {
        script.setText(FileUtil.toSystemDependentName(configuration.getScriptPath()));
        parameters.setText(configuration.getScriptParameters());
        workingDirectory.setText(FileUtil.toSystemDependentName(configuration.getWorkingDirectory()));
        compileFirst.setSelected(configuration.isCompileFirst());
        exeConsole.setSelectedItem(configuration.getExeConsole());
        exeConsole.setEnabled(configuration.isCompileFirst());
    }

    /** Writes the form into the configuration. */
    @Override
    protected void applyEditorTo(@NotNull AhkRunConfiguration configuration) {
        configuration.setScriptPath(script.getText());
        configuration.setScriptParameters(parameters.getText());
        configuration.setWorkingDirectory(workingDirectory.getText());
        configuration.setCompileFirst(compileFirst.isSelected());
        if (exeConsole.getSelectedItem() instanceof AhkRunConfiguration.ExeConsole console) {
            configuration.setExeConsole(console);
        }
    }

    /** The form panel. */
    @Override
    protected @NotNull JComponent createEditor() {
        return panel;
    }
}
