package de.kumpewo.autohotkey.formatter;

import de.kumpewo.autohotkey.AhkBundle;
import com.intellij.application.options.CodeStyleAbstractPanel;
import com.intellij.openapi.editor.colors.EditorColorsScheme;
import com.intellij.openapi.editor.highlighter.EditorHighlighter;
import com.intellij.openapi.editor.highlighter.EditorHighlighterFactory;
import com.intellij.openapi.fileTypes.FileType;
import com.intellij.psi.codeStyle.CodeStyleSettings;
import com.intellij.testFramework.LightVirtualFile;
import com.intellij.ui.JBSplitter;
import com.intellij.ui.components.JBScrollPane;
import com.intellij.util.ui.JBUI;
import de.kumpewo.autohotkey.AhkFileType;
import de.kumpewo.autohotkey.AhkLanguage;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import javax.swing.*;
import java.awt.*;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Tab "Case" of the code style page: main switch, one switch for all built-in names and, for each group of
 * keyword-case.txt, a choice "as in list" / "lowercase" / "keep". With a preview.
 */
final class AhkCaseCodeStylePanel extends CodeStyleAbstractPanel {

    private final JPanel panel = new JPanel(new BorderLayout());
    private final JCheckBox adjust = new JCheckBox(AhkBundle.message("case.adjust"));
    private final JComboBox<AhkCaseMode> builtin = new JComboBox<>(new AhkCaseMode[]{AhkCaseMode.AS_LIST, AhkCaseMode.KEEP});
    private final Map<String, JComboBox<AhkCaseMode>> groups = new LinkedHashMap<>();

    AhkCaseCodeStylePanel(@Nullable CodeStyleSettings currentSettings, @NotNull CodeStyleSettings settings) {
        super(AhkLanguage.INSTANCE, currentSettings, settings);

        JPanel options = new JPanel(new GridBagLayout());
        GridBagConstraints c = new GridBagConstraints();
        c.anchor = GridBagConstraints.WEST;
        c.insets = JBUI.insets(2, 4);
        c.gridx = 0;
        c.gridy = 0;
        c.gridwidth = 2;
        options.add(adjust, c);
        c.gridwidth = 1;
        addRow(options, c, AhkBundle.message("case.builtin"), builtin);
        c.gridy++;
        c.gridx = 0;
        c.gridwidth = 2;
        c.insets = JBUI.insets(10, 4, 2, 4);
        options.add(new JLabel(AhkBundle.message("case.keywords")), c);
        c.insets = JBUI.insets(2, 4);
        c.gridwidth = 1;
        for (AhkKeywordCase.Group group : AhkKeywordCase.groups()) {
            JComboBox<AhkCaseMode> box = new JComboBox<>(AhkCaseMode.values());
            box.setToolTipText(String.join(" ", group.words().values()));
            groups.put(group.name(), box);
            addRow(options, c, group.name() + ":", box);
        }
        c.gridy++;
        c.weighty = 1;
        options.add(new JPanel(), c);   // push the rest to the top

        JPanel preview = new JPanel(new BorderLayout());
        installPreviewPanel(preview);
        JBSplitter splitter = new JBSplitter(false, 0.45f);
        splitter.setFirstComponent(new JBScrollPane(options));
        splitter.setSecondComponent(preview);
        panel.add(splitter, BorderLayout.CENTER);

        addPanelToWatch(options);   // every change updates the preview
        resetImpl(settings);
    }

    /** Adds a row "label: component" to the grid. */
    private static void addRow(JPanel options, GridBagConstraints c, String label, JComponent box) {
        c.gridy++;
        c.gridx = 0;
        c.weightx = 0;
        options.add(new JLabel(label), c);
        c.gridx = 1;
        c.weightx = 1;
        options.add(box, c);
    }

    /** Title of the tab. */
    @Override
    protected String getTabTitle() {
        return AhkBundle.message("case.tab");
    }

    /** Right margin of the preview. */
    @Override
    protected int getRightMargin() {
        return 120;
    }

    /** Highlighter of the preview. */
    @Override
    protected @Nullable EditorHighlighter createHighlighter(@NotNull EditorColorsScheme scheme) {
        return EditorHighlighterFactory.getInstance()
                .createEditorHighlighter(new LightVirtualFile("a.ahk"), scheme, null);
    }

    /** File type of the preview. */
    @Override
    protected @NotNull FileType getFileType() {
        return AhkFileType.INSTANCE;
    }

    /** Text of the preview. */
    @Override
    protected String getPreviewText() {
        return SAMPLE;
    }

    /** Writes the switches into the settings. */
    @Override
    public void apply(@NotNull CodeStyleSettings settings) {
        AhkCodeStyleSettings custom = settings.getCustomSettings(AhkCodeStyleSettings.class);
        custom.ADJUST_CASE = adjust.isSelected();
        custom.ADJUST_BUILTIN_CASE = builtin.getSelectedItem() == AhkCaseMode.AS_LIST;
        for (Map.Entry<String, JComboBox<AhkCaseMode>> e : groups.entrySet()) {
            AhkCaseMode mode = (AhkCaseMode) e.getValue().getSelectedItem();
            if (mode != null && mode != custom.keywordCase(e.getKey())) custom.setKeywordCase(e.getKey(), mode);
        }
    }

    /** True if a switch differs from the settings. */
    @Override
    public boolean isModified(@NotNull CodeStyleSettings settings) {
        AhkCodeStyleSettings custom = settings.getCustomSettings(AhkCodeStyleSettings.class);
        if (custom.ADJUST_CASE != adjust.isSelected()) return true;
        if (custom.ADJUST_BUILTIN_CASE != (builtin.getSelectedItem() == AhkCaseMode.AS_LIST)) return true;
        for (Map.Entry<String, JComboBox<AhkCaseMode>> e : groups.entrySet()) {
            if (custom.keywordCase(e.getKey()) != e.getValue().getSelectedItem()) return true;
        }
        return false;
    }

    /** The panel of the tab. */
    @Override
    public @NotNull JComponent getPanel() {
        return panel;
    }

    /** Sets the switches from the settings. */
    @Override
    protected void resetImpl(@NotNull CodeStyleSettings settings) {
        AhkCodeStyleSettings custom = settings.getCustomSettings(AhkCodeStyleSettings.class);
        adjust.setSelected(custom.ADJUST_CASE);
        builtin.setSelectedItem(custom.ADJUST_BUILTIN_CASE ? AhkCaseMode.AS_LIST : AhkCaseMode.KEEP);
        for (Map.Entry<String, JComboBox<AhkCaseMode>> e : groups.entrySet()) {
            e.getValue().setSelectedItem(custom.keywordCase(e.getKey()));
        }
    }

    // Preview: deliberately mixed case
    private static final String SAMPLE = """
            #include <MyLib>
            #SINGLEINSTANCE Force

            CLASS Box EXTENDS gui.edit {
                STATIC count := 0
            }

            msgbox(strlen(a_scriptdir))
            LOOP PARSE, "a,b", ","
                IF (a_loopfield = "a" AND NOT TRUE)
                    BREAK
            SWITCH x {
                CASE 1: RETURN
                DEFAULT: arr.PUSH(THIS.LENGTH)
            }
            """;
}
