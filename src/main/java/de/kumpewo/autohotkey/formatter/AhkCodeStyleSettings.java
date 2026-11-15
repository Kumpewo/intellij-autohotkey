package de.kumpewo.autohotkey.formatter;

import com.intellij.psi.codeStyle.CodeStyleSettings;
import com.intellij.psi.codeStyle.CustomCodeStyleSettings;
import org.jetbrains.annotations.NotNull;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * AHK-specific code style options that don't exist as standard options. Indent, continuation indent and most
 * spacing rules use the standard options (CommonCodeStyleSettings).
 *
 * A new option is just a public field, e.g.
 *     public boolean SPACE_AFTER_HOTKEY_SEPARATOR = false;
 * It is saved automatically; it only appears in the dialog once the LanguageCodeStyleSettingsProvider
 * registers it with consumer.showCustomOption(...).
 * Reading: settings.getCustomSettings(AhkCodeStyleSettings.class).
 */
public class AhkCodeStyleSettings extends CustomCodeStyleSettings {
    /** Spaces inside the brackets of an index access: a[ 1 ] instead of a[1]. (Array literal: standard option SPACE_WITHIN_BRACKETS) */
    public boolean SPACE_WITHIN_INDEX_BRACKETS = false;

    /** "Reformat Code" adjusts the case of known words (main switch, default off). */
    public boolean ADJUST_CASE = false;

    /** Built-in names (variables, functions, classes, methods, properties, directives) as in the list. */
    public boolean ADJUST_BUILTIN_CASE = true;

    /**
     * Switch per keyword group (keyword-case.txt) as "Group=MODE;Group=MODE" (MODE = constant of AhkCaseMode).
     * If a group is missing, its default from the file applies. A string instead of a map because the platform
     * only stores simple fields and copies them flat when cloning.
     */
    public String KEYWORD_CASE = "";

    // Doc comments (Z.23, tab "Doc Comments", AhkDocCommentFormatter); defaults like JavaDoc, main switch off
    /** "Reformat Code" formats doc comments (main switch). Without it only the * lines are aligned. */
    public boolean DOC_FORMAT = false;
    /** Descriptions of all @param in one column. */
    public boolean DOC_ALIGN_PARAM_DESCRIPTIONS = true;
    /** {Type} and name of the @param lines each in a column. */
    public boolean DOC_ALIGN_TYPES_AND_NAMES = true;
    /** Continuation lines of a tag indented by one level instead of below the text. */
    public boolean DOC_INDENT_CONTINUATION = false;
    /** The description of a @param in the line below "@param name". */
    public boolean DOC_PARAM_DESCRIPTION_ON_NEW_LINE = false;
    /** Blank line between the description and the tags. */
    public boolean DOC_BLANK_AFTER_DESCRIPTION = true;
    /** Blank line after the @param block. */
    public boolean DOC_BLANK_AFTER_PARAMS = false;
    /** Blank line after @returns. */
    public boolean DOC_BLANK_AFTER_RETURNS = false;
    /** Blank line before every @example. */
    public boolean DOC_BLANK_BEFORE_EXAMPLE = false;
    /** Wrap the description and the tag texts at the right margin. */
    public boolean DOC_WRAP = true;
    /** Keep a comment in one line ({@code /** Text. *}{@code /}) instead of three. */
    public boolean DOC_KEEP_ONE_LINE = false;
    /** Don't join the lines of a paragraph, only wrap lines that are too long. */
    public boolean DOC_PRESERVE_LINE_FEEDS = false;
    /** Every line starts with " * " (missing stars are added). */
    public boolean DOC_LEADING_ASTERISKS = true;
    /** Keep @param tags without a parameter of that name (off = remove them). */
    public boolean DOC_KEEP_INVALID_TAGS = true;
    /** Keep @param, @returns and @throws without text (off = remove them). */
    public boolean DOC_KEEP_EMPTY_TAGS = true;
    /** Sort the @param tags in the order of the parameters. */
    public boolean DOC_SORT_PARAMS = false;
    /** Add "@param name" for every parameter without a @param tag. */
    public boolean DOC_ADD_MISSING_PARAMS = false;

    public AhkCodeStyleSettings(CodeStyleSettings settings) {
        super("AhkCodeStyleSettings", settings);
    }

    /** Switch of the group; not set = default from keyword-case.txt. */
    public @NotNull AhkCaseMode keywordCase(@NotNull String group) {
        AhkCaseMode mode = keywordCaseMap().get(group);
        if (mode != null) return mode;
        for (AhkKeywordCase.Group g : AhkKeywordCase.groups()) {
            if (g.name().equals(group)) return g.defaultMode();
        }
        return AhkCaseMode.KEEP;
    }

    /** Sets the switch of a group (rewrites KEYWORD_CASE). */
    public void setKeywordCase(@NotNull String group, @NotNull AhkCaseMode mode) {
        Map<String, AhkCaseMode> map = keywordCaseMap();
        map.put(group, mode);
        StringBuilder sb = new StringBuilder();
        for (Map.Entry<String, AhkCaseMode> e : map.entrySet()) {
            if (!sb.isEmpty()) sb.append(';');
            sb.append(e.getKey()).append('=').append(e.getValue().name());
        }
        KEYWORD_CASE = sb.toString();
    }

    /** KEYWORD_CASE parsed into a map group -> mode (unreadable entries are skipped). */
    private Map<String, AhkCaseMode> keywordCaseMap() {
        Map<String, AhkCaseMode> map = new LinkedHashMap<>();
        if (KEYWORD_CASE == null) return map;
        for (String entry : KEYWORD_CASE.split(";")) {
            int eq = entry.indexOf('=');
            if (eq <= 0) continue;
            AhkCaseMode mode = AhkCaseMode.parse(entry.substring(eq + 1));
            if (mode != null) map.put(entry.substring(0, eq), mode);
        }
        return map;
    }
}
