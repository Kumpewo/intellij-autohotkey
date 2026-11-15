package de.kumpewo.autohotkey.highlighting;

import com.intellij.openapi.editor.DefaultLanguageHighlighterColors;
import com.intellij.openapi.editor.colors.EditorColors;
import com.intellij.openapi.editor.colors.TextAttributesKey;

import static com.intellij.openapi.editor.colors.TextAttributesKey.createTextAttributesKey;

/**
 * Color keys of the language. Defaults come from the platform keys given here and from
 * {@code colorSchemes/AhkDefault.xml}; the user changes them on the color settings page
 * ({@link AhkColorSettingsPage}).
 */
public class AhkColors {
    public static final TextAttributesKey STRING =
            createTextAttributesKey("AHK_STRING", DefaultLanguageHighlighterColors.STRING);

    public static final TextAttributesKey NUMBER =
            createTextAttributesKey("AHK_NUMBER", DefaultLanguageHighlighterColors.NUMBER);

    public static final TextAttributesKey COMMENT =
            createTextAttributesKey("AHK_COMMENT", DefaultLanguageHighlighterColors.LINE_COMMENT);

    // class definition
    public static final TextAttributesKey AHK_CLASS =
            createTextAttributesKey("AHK_CLASS",
                    DefaultLanguageHighlighterColors.CLASS_NAME);

    // function / method definition
    public static final TextAttributesKey AHK_FUNC =
            createTextAttributesKey("AHK_FUNC",
                    DefaultLanguageHighlighterColors.FUNCTION_DECLARATION);

    // #-commands
    public static final TextAttributesKey AHK_DIRECTIVE =
            createTextAttributesKey("AHK_DIRECTIVE",
                    DefaultLanguageHighlighterColors.KEYWORD);

    // if, loop, switch, case, default (in a switch), Loop Files/Parse/Read/Reg ...
    public static final TextAttributesKey AHK_FLOWCONTROL =
            createTextAttributesKey("AHK_FLOWCONTROL",
                    DefaultLanguageHighlighterColors.KEYWORD);

    // and, or, not, is, in, contains, as, local/global/static, true/false/unset/super, class, extends, this
    public static final TextAttributesKey AHK_RESERVED =
            createTextAttributesKey("AHK_RESERVED",
                    DefaultLanguageHighlighterColors.PREDEFINED_SYMBOL);

    // predefined/built-in functions
    public static final TextAttributesKey AHK_BUILTIN_FUNCTION =
            createTextAttributesKey("AHK_BUILTIN_FUNCTION",
                    DefaultLanguageHighlighterColors.FUNCTION_DECLARATION);

    // A_-vars and more
    public static final TextAttributesKey AHK_BUILTIN_VARIABLE =
            createTextAttributesKey("AHK_BUILTIN_VARIABLE",
                    DefaultLanguageHighlighterColors.CONSTANT);

    // class names
    public static final TextAttributesKey AHK_BUILTIN_CLASS =
            createTextAttributesKey("AHK_BUILTIN_CLASS",
                    DefaultLanguageHighlighterColors.CLASS_NAME);

    // predefined methods
    public static final TextAttributesKey AHK_BUILTIN_METHOD =
            createTextAttributesKey("AHK_BUILTIN_METHOD",
                    DefaultLanguageHighlighterColors.INSTANCE_METHOD);

    // predefined properties (arr.Length, m.Default)
    public static final TextAttributesKey AHK_BUILTIN_PROPERTY =
            createTextAttributesKey("AHK_BUILTIN_PROPERTY",
                    DefaultLanguageHighlighterColors.INSTANCE_FIELD);

    // name with a type declaration (;@declare-type g as Gui, ;@declare-auto-type ...)
    public static final TextAttributesKey AHK_DECLARED_TYPE =
            createTextAttributesKey("AHK_DECLARED_TYPE",
                    DefaultLanguageHighlighterColors.LOCAL_VARIABLE);

    // member declared private with ;@declare-private (italic gray in the schemes AhkDefault.xml/AhkDarcula.xml)
    public static final TextAttributesKey AHK_PRIVATE_MEMBER =
            createTextAttributesKey("AHK_PRIVATE_MEMBER",
                    DefaultLanguageHighlighterColors.INSTANCE_FIELD);

    // label:
    public static final TextAttributesKey AHK_LABEL =
            createTextAttributesKey("AHK_LABEL",
                    DefaultLanguageHighlighterColors.LABEL);

    // X::
    public static final TextAttributesKey AHK_HOTKEY =
            createTextAttributesKey("AHK_HOTKEY",
                    DefaultLanguageHighlighterColors.KEYWORD);

    // ::Y::
    public static final TextAttributesKey AHK_HOTSTRING =
            createTextAttributesKey("AHK_HOTSTRING",
                    DefaultLanguageHighlighterColors.KEYWORD);

    // line after ";@ignore-next-line" (AhkIgnoredLineAnnotator): marked background, normal text color
    public static final TextAttributesKey AHK_IGNORED_LINE =
            createTextAttributesKey("AHK_IGNORED_LINE",
                    EditorColors.INJECTED_LANGUAGE_FRAGMENT);

    // escape sequence in a string: `n `t `" ... (AhkAnnotator)
    public static final TextAttributesKey AHK_STRING_ESCAPE =
            createTextAttributesKey("AHK_STRING_ESCAPE",
                    DefaultLanguageHighlighterColors.VALID_STRING_ESCAPE);
}
