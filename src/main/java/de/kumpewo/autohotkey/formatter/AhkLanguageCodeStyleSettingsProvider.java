package de.kumpewo.autohotkey.formatter;

import com.intellij.lang.Language;
import com.intellij.psi.codeStyle.CodeStyleSettingsCustomizable;
import com.intellij.psi.codeStyle.CodeStyleSettingsCustomizableOptions;
import com.intellij.psi.codeStyle.CommonCodeStyleSettings;
import com.intellij.psi.codeStyle.LanguageCodeStyleSettingsProvider;
import de.kumpewo.autohotkey.AhkLanguage;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * Defines WHICH standard options appear on the code style page and their defaults.
 * An option shown here only has an effect if AhkFormattingModelBuilder.createSpacingBuilder reads it.
 */
final class AhkLanguageCodeStyleSettingsProvider extends LanguageCodeStyleSettingsProvider {

    /** AutoHotkey. */
    @NotNull
    @Override
    public Language getLanguage() {
        return AhkLanguage.INSTANCE;
    }

    /** The options shown on the tabs "Tabs and Indents", "Spaces" and "Blank Lines". */
    @Override
    public void customizeSettings(@NotNull CodeStyleSettingsCustomizable consumer, @NotNull SettingsType settingsType) {
        if (settingsType == SettingsType.INDENT_SETTINGS) {
            consumer.showStandardOptions("INDENT_SIZE", "CONTINUATION_INDENT_SIZE", "TAB_SIZE", "USE_TAB_CHARACTER");

        } else if (settingsType == SettingsType.SPACING_SETTINGS) {
            consumer.showStandardOptions(
                    // operators
                    "SPACE_AROUND_ASSIGNMENT_OPERATORS",
                    "SPACE_AROUND_LAMBDA_ARROW",
                    "SPACE_AROUND_LOGICAL_OPERATORS",
                    "SPACE_AROUND_EQUALITY_OPERATORS",
                    "SPACE_AROUND_RELATIONAL_OPERATORS",
                    "SPACE_AROUND_BITWISE_OPERATORS",
                    "SPACE_AROUND_ADDITIVE_OPERATORS",
                    "SPACE_AROUND_MULTIPLICATIVE_OPERATORS",
                    "SPACE_AROUND_SHIFT_OPERATORS",
                    "SPACE_AROUND_UNARY_OPERATOR",
                    // within brackets
                    "SPACE_WITHIN_PARENTHESES",
                    "SPACE_WITHIN_METHOD_CALL_PARENTHESES",
                    "SPACE_WITHIN_METHOD_PARENTHESES",
                    "SPACE_WITHIN_BRACKETS",
                    "SPACE_WITHIN_BRACES",
                    // before { (function/class)
                    "SPACE_BEFORE_METHOD_LBRACE",
                    "SPACE_BEFORE_CLASS_LBRACE",
                    // other
                    "SPACE_BEFORE_COMMA", "SPACE_AFTER_COMMA",
                    "SPACE_BEFORE_QUEST", "SPACE_AFTER_QUEST",
                    "SPACE_BEFORE_COLON", "SPACE_AFTER_COLON");

            consumer.renameStandardOption("SPACE_WITHIN_BRACKETS", "Array literal brackets ([ a, b ])");
            consumer.showCustomOption(AhkCodeStyleSettings.class, "SPACE_WITHIN_INDEX_BRACKETS",
                    "Index access brackets (a[1])", CodeStyleSettingsCustomizableOptions.getInstance().SPACES_WITHIN);
            consumer.renameStandardOption("SPACE_AROUND_ASSIGNMENT_OPERATORS", "Assignment operators (:=, +=, ...)");
            consumer.renameStandardOption("SPACE_AROUND_LAMBDA_ARROW", "Fat arrow (=>)");
            consumer.renameStandardOption("SPACE_AROUND_LOGICAL_OPERATORS", "Logical operators (&&, ||, ??)");
            consumer.renameStandardOption("SPACE_AROUND_EQUALITY_OPERATORS", "Equality operators (=, ==, !=)");
            consumer.renameStandardOption("SPACE_AROUND_RELATIONAL_OPERATORS", "Relational operators (<, >, <=, >=, ~=)");
            consumer.renameStandardOption("SPACE_AROUND_MULTIPLICATIVE_OPERATORS", "Multiplicative operators (*, /, //, **)");
            consumer.renameStandardOption("SPACE_WITHIN_METHOD_PARENTHESES", "Function declaration parentheses");
            consumer.renameStandardOption("SPACE_WITHIN_METHOD_CALL_PARENTHESES", "Function call parentheses");
            consumer.renameStandardOption("SPACE_BEFORE_METHOD_LBRACE", "Function left brace");
            consumer.renameStandardOption("SPACE_BEFORE_CLASS_LBRACE", "Class left brace");

        } else if (settingsType == SettingsType.BLANK_LINES_SETTINGS) {
            consumer.showStandardOptions("KEEP_BLANK_LINES_IN_CODE");
        }
        // WRAPPING_AND_BRACES_SETTINGS deliberately not offered: KEEP_LINE_BREAKS stays on,
        // otherwise spacing rules would join lines (end of line = end of statement!).
    }

    /** Defaults: indent 4, continuation indent 8, spaces instead of tabs, spaces inside array literals. */
    @Override
    public @Nullable CommonCodeStyleSettings getDefaultCommonSettings() {
        CommonCodeStyleSettings defaults = new CommonCodeStyleSettings(getLanguage());
        CommonCodeStyleSettings.IndentOptions indent = defaults.initIndentOptions();
        indent.INDENT_SIZE = 4;
        indent.CONTINUATION_INDENT_SIZE = 8;   // continuation lines further than the normal indent (as in Java)
        indent.TAB_SIZE = 4;
        indent.USE_TAB_CHARACTER = false;
        defaults.KEEP_BLANK_LINES_IN_CODE = 2;
        defaults.SPACE_WITHIN_BRACKETS = true;   // array literal: [ a, b ]; index access stays a[1] (own option)
        return defaults;
    }

    /** Preview text on all tabs. */
    @Override
    public String getCodeSample(@NotNull SettingsType settingsType) {
        return SAMPLE;
    }

    // Only constructs the parser already supports (no implicit concatenation).
    private static final String SAMPLE = """
            ; AutoHotkey v2
            class Counter extends Base {
                static instances := 0
                count := 0

                __New(start := 0, step := 1) {
                    this.count := start
                    this.step := step
                }

                Next() {
                    this.count += this.step
                    return this.count
                }
            }

            Twice(x) => x * 2

            items := [1, 2, 3]
            first := items[1]
            point := {x: 10, y: 20}
            total := 0
            for index, item in items {
                if (item > 1 and index != 2)
                    total += Twice(item) - -1
                else if (item = 1) {
                    total++
                } else {
                    total -= 1
                }
            }
            result := total > 5 ? "big" : "small"
            limit := (total > 100 and
                total < 1000)
            MsgBox("Total", "Result")

            ^!r::Reload
            """;
}
