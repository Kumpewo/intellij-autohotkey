package de.kumpewo.autohotkey.formatter;

import com.intellij.application.options.CodeStyleAbstractPanel;
import com.intellij.openapi.editor.colors.EditorColorsScheme;
import com.intellij.openapi.editor.highlighter.EditorHighlighter;
import com.intellij.openapi.editor.highlighter.EditorHighlighterFactory;
import com.intellij.openapi.fileTypes.FileType;
import com.intellij.psi.codeStyle.CodeStyleSettings;
import com.intellij.testFramework.LightVirtualFile;
import com.intellij.ui.JBSplitter;
import com.intellij.ui.TitledSeparator;
import com.intellij.ui.components.JBScrollPane;
import com.intellij.util.ui.JBUI;
import de.kumpewo.autohotkey.AhkBundle;
import de.kumpewo.autohotkey.AhkFileType;
import de.kumpewo.autohotkey.AhkLanguage;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import javax.swing.*;
import java.awt.*;
import java.lang.reflect.Field;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Tab "Doc Comments" of the code style page: main switch and the switches of {@link AhkDocCommentFormatter} in
 * the groups Alignment, Blank lines, Other and Tags, like the JavaDoc tab of Java. With a preview. The switches are
 * the boolean fields DOC_* of {@link AhkCodeStyleSettings} (read and written by name).
 */
final class AhkDocCommentCodeStylePanel extends CodeStyleAbstractPanel {

    /** The groups with their switches: bundle key of the title, then pairs (field, bundle key of the label). */
    private static final List<String[]> GROUPS = List.of(
            new String[]{"doc.style.group.alignment",
                    "DOC_ALIGN_PARAM_DESCRIPTIONS", "doc.style.align.descriptions",
                    "DOC_ALIGN_TYPES_AND_NAMES", "doc.style.align.types",
                    "DOC_INDENT_CONTINUATION", "doc.style.indent.continuation",
                    "DOC_PARAM_DESCRIPTION_ON_NEW_LINE", "doc.style.description.new.line"},
            new String[]{"doc.style.group.blank",
                    "DOC_BLANK_AFTER_DESCRIPTION", "doc.style.blank.description",
                    "DOC_BLANK_AFTER_PARAMS", "doc.style.blank.params",
                    "DOC_BLANK_AFTER_RETURNS", "doc.style.blank.returns",
                    "DOC_BLANK_BEFORE_EXAMPLE", "doc.style.blank.example"},
            new String[]{"doc.style.group.other",
                    "DOC_WRAP", "doc.style.wrap",
                    "DOC_KEEP_ONE_LINE", "doc.style.one.line",
                    "DOC_PRESERVE_LINE_FEEDS", "doc.style.line.feeds",
                    "DOC_LEADING_ASTERISKS", "doc.style.asterisks"},
            new String[]{"doc.style.group.tags",
                    "DOC_KEEP_INVALID_TAGS", "doc.style.keep.invalid",
                    "DOC_KEEP_EMPTY_TAGS", "doc.style.keep.empty",
                    "DOC_SORT_PARAMS", "doc.style.sort.params",
                    "DOC_ADD_MISSING_PARAMS", "doc.style.add.missing"});

    private final JPanel panel = new JPanel(new BorderLayout());
    private final JCheckBox format = new JCheckBox(AhkBundle.message("doc.style.format"));
    /** Field name -> check box. */
    private final Map<String, JCheckBox> boxes = new LinkedHashMap<>();

    AhkDocCommentCodeStylePanel(@Nullable CodeStyleSettings currentSettings, @NotNull CodeStyleSettings settings) {
        super(AhkLanguage.INSTANCE, currentSettings, settings);
        JPanel options = new JPanel(new GridBagLayout());
        GridBagConstraints c = new GridBagConstraints();
        c.anchor = GridBagConstraints.WEST;
        c.fill = GridBagConstraints.HORIZONTAL;
        c.weightx = 1;
        c.gridx = 0;
        c.gridy = 0;
        c.insets = JBUI.insets(2, 4);
        options.add(format, c);
        c.gridy++;
        JLabel hint = new JLabel(AhkBundle.message("doc.style.hint"));
        hint.setForeground(com.intellij.util.ui.UIUtil.getContextHelpForeground());
        options.add(hint, c);
        for (String[] group : GROUPS) {
            c.gridy++;
            c.insets = JBUI.insets(10, 4, 2, 4);
            options.add(new TitledSeparator(AhkBundle.message(group[0])), c);
            c.insets = JBUI.insets(2, 16, 2, 4);
            for (int i = 1; i + 1 < group.length; i += 2) {
                JCheckBox box = new JCheckBox(AhkBundle.message(group[i + 1]));
                boxes.put(group[i], box);
                c.gridy++;
                options.add(box, c);
            }
        }
        c.gridy++;
        c.weighty = 1;
        options.add(new JPanel(), c);   // push the rest to the top
        format.addActionListener(e -> updateEnabled());

        JPanel preview = new JPanel(new BorderLayout());
        installPreviewPanel(preview);
        JBSplitter splitter = new JBSplitter(false, 0.45f);
        splitter.setFirstComponent(new JBScrollPane(options));
        splitter.setSecondComponent(preview);
        panel.add(splitter, BorderLayout.CENTER);

        addPanelToWatch(options);   // every change updates the preview
        resetImpl(settings);
    }

    /** The switches only matter with the main switch. */
    private void updateEnabled() {
        for (JCheckBox box : boxes.values()) box.setEnabled(format.isSelected());
    }

    /** Title of the tab. */
    @Override
    protected String getTabTitle() {
        return AhkBundle.message("doc.style.tab");
    }

    /** Right margin of the preview (narrow, so that the wrapping is visible). */
    @Override
    protected int getRightMargin() {
        return 60;
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
        custom.DOC_FORMAT = format.isSelected();
        for (Map.Entry<String, JCheckBox> e : boxes.entrySet()) set(custom, e.getKey(), e.getValue().isSelected());
    }

    /** True if a switch differs from the settings. */
    @Override
    public boolean isModified(@NotNull CodeStyleSettings settings) {
        AhkCodeStyleSettings custom = settings.getCustomSettings(AhkCodeStyleSettings.class);
        if (custom.DOC_FORMAT != format.isSelected()) return true;
        for (Map.Entry<String, JCheckBox> e : boxes.entrySet()) {
            if (get(custom, e.getKey()) != e.getValue().isSelected()) return true;
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
        format.setSelected(custom.DOC_FORMAT);
        for (Map.Entry<String, JCheckBox> e : boxes.entrySet()) e.getValue().setSelected(get(custom, e.getKey()));
        updateEnabled();
    }

    /** Value of a boolean field of the settings. */
    static boolean get(AhkCodeStyleSettings settings, String field) {
        try {
            return settings.getClass().getField(field).getBoolean(settings);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("Unknown doc comment option " + field, e);
        }
    }

    /** Sets a boolean field of the settings. */
    static void set(AhkCodeStyleSettings settings, String field, boolean value) {
        try {
            Field f = settings.getClass().getField(field);
            f.setBoolean(settings, value);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("Unknown doc comment option " + field, e);
        }
    }

    /** Names of all switch fields (for the test that every field exists). */
    static List<String> fields() {
        List<String> names = new java.util.ArrayList<>();
        for (String[] group : GROUPS) {
            for (int i = 1; i + 1 < group.length; i += 2) names.add(group[i]);
        }
        return names;
    }

    // Preview: untidy doc comments in a class (the preview formats through the PSI, AhkFormattingModel keeps its
    // line breaks)
    private static final String SAMPLE = """
            class Box {
            /**
            Opens the box and counts the openings. A long description is wrapped at the right margin.
                 * @param {String} name name of the box
              * @param {Boolean} force open even if the box is locked by someone else
             * @param &count
             * @returns {Integer} the new number of openings
             * @example
             *   b := Box()
             *   b.Open("tools")
             * @see Close
             */
            Open(name, force := false, &count := 0, extra?) {
            return 1
            }

            /** Closes the box. */
            Close() {
            }
            }
            """;
}
