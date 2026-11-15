package de.kumpewo.autohotkey.highlighting;

import com.intellij.openapi.editor.colors.TextAttributesKey;
import de.kumpewo.autohotkey.AhkBundle;
import com.intellij.openapi.fileTypes.SyntaxHighlighter;
import com.intellij.openapi.options.colors.AttributesDescriptor;
import com.intellij.openapi.options.colors.ColorDescriptor;
import com.intellij.openapi.options.colors.ColorSettingsPage;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import javax.swing.*;
import java.util.HashMap;
import java.util.Map;

/**
 * Settings page "Editor > Color Scheme > AutoHotkey": the configurable colors in the groups Core, Declaration,
 * Built-in elements and Special, and a preview text in which the annotator-based colors are marked with tags.
 */
public class AhkColorSettingsPage implements ColorSettingsPage {

    /**
     * The configurable colors: bundle key (without the prefix "color.") and key. The display text "Group//Name" puts
     * the entry into a group of the tree on the page. The IDE sorts groups and entries alphabetically by the whole
     * display name (ColorOptionsTree, no way to switch it off); the order here only serves the overview.
     */
    private static final class Tables {
        static final Object[][] COLORS = {
                {"core.flow", AhkColors.AHK_FLOWCONTROL},
                {"core.reserved", AhkColors.AHK_RESERVED},
                {"core.directive", AhkColors.AHK_DIRECTIVE},
                {"core.number", AhkColors.NUMBER},
                {"core.string", AhkColors.STRING},
                {"core.label", AhkColors.AHK_LABEL},
                {"core.comment", AhkColors.COMMENT},
                {"declaration.function", AhkColors.AHK_FUNC},
                {"declaration.class", AhkColors.AHK_CLASS},
                {"declaration.hotkey", AhkColors.AHK_HOTKEY},
                {"declaration.hotstring", AhkColors.AHK_HOTSTRING},
                {"builtin.function", AhkColors.AHK_BUILTIN_FUNCTION},
                {"builtin.variable", AhkColors.AHK_BUILTIN_VARIABLE},
                {"builtin.class", AhkColors.AHK_BUILTIN_CLASS},
                {"builtin.method", AhkColors.AHK_BUILTIN_METHOD},
                {"builtin.property", AhkColors.AHK_BUILTIN_PROPERTY},
                {"special.escape", AhkColors.AHK_STRING_ESCAPE},
                {"special.declared", AhkColors.AHK_DECLARED_TYPE},
                {"special.private", AhkColors.AHK_PRIVATE_MEMBER},
                {"special.ignored", AhkColors.AHK_IGNORED_LINE},
        };
    }

    /** No icon. */
    @Override
    public @Nullable Icon getIcon() {
        return null;
    }

    /** Lexer-based highlighting of the preview. */
    @Override
    @NotNull
    public SyntaxHighlighter getHighlighter() {
        return new AhkSyntaxHighlighter();
    }

    /** Preview text; tags mark the colors that the annotators assign in a real editor. */
    @Override
    @NotNull
    public String getDemoText() {
        // Sample text shown in the preview on the settings page
        return """
                /* This is a preview example.
                */
                <DIRECTIVE>#requires</DIRECTIVE> AutoHotkey v2.0
                <DIRECTIVE>#SingleInstance</DIRECTIVE> force

                <HOTSTRING>:*:btw::by the way</HOTSTRING>
                <HOTKEY>^m::</HOTKEY><BUILTIN_FUNCTION>MsgBox</BUILTIN_FUNCTION>("Hi<ESC>`n</ESC>Line 2 with <ESC>`"</ESC>quote<ESC>`"</ESC>")

                ;@declare-private _*
                <RESERVED>class</RESERVED> <CLASS>MyEdit</CLASS> <RESERVED>extends</RESERVED> <BUILTIN_CLASS>Gui</BUILTIN_CLASS>.<BUILTIN_CLASS>Edit</BUILTIN_CLASS> {
                    <PRIVATE>_count</PRIVATE> := 0
                    <FUNC>example_fun</FUNC>() {
                        <RESERVED>this</RESERVED>.<BUILTIN_METHOD>Focus</BUILTIN_METHOD>()
                        <RESERVED>this</RESERVED>.<PRIVATE>_count</PRIVATE> += 1
                        <FLOW>return</FLOW> <RESERVED>this</RESERVED>.<BUILTIN_PROPERTY>Value</BUILTIN_PROPERTY>
                    }
                }

                ;@declare-type myGui as Gui, *Edit as Gui.Edit
                <DECLARED>myGui</DECLARED> := <BUILTIN_CLASS>Gui</BUILTIN_CLASS>()
                <DECLARED>nameEdit</DECLARED> := <DECLARED>myGui</DECLARED>.<BUILTIN_METHOD>Add</BUILTIN_METHOD>("Edit")

                a := 0
                <FLOW>Loop</FLOW> 5 {
                    a += <BUILTIN_VARIABLE>A_Index</BUILTIN_VARIABLE> ** 2
                }
                <FLOW>Loop</FLOW> <FLOW>Parse</FLOW> "a,b", ","
                    <BUILTIN_FUNCTION>ToolTip</BUILTIN_FUNCTION> <BUILTIN_VARIABLE>A_LoopField</BUILTIN_VARIABLE>

                <FLOW>switch</FLOW> a {
                <FLOW>case</FLOW> 1: a := 2
                <FLOW>default</FLOW>: a := 3
                }

                <FLOW>if</FLOW> (<RESERVED>true</RESERVED> <RESERVED>and</RESERVED> a)
                    <FLOW>goto</FLOW> skip
                a := 42

                <LABEL>skip:</LABEL>

                ;@ignore-next-line the parser does not support SetTimer without parentheses
                <IGNORED>SetTimer () => MsgBox("x"), 100</IGNORED>

                """;
    }

    /** Tags of the preview text and their colors. */
    @Override
    @Nullable
    public Map<String, TextAttributesKey> getAdditionalHighlightingTagToDescriptorMap() {
        Map<String, TextAttributesKey> map = new HashMap<>();
        map.put("BUILTIN_VARIABLE", AhkColors.AHK_BUILTIN_VARIABLE);
        map.put("BUILTIN_FUNCTION", AhkColors.AHK_BUILTIN_FUNCTION);
        map.put("BUILTIN_CLASS", AhkColors.AHK_BUILTIN_CLASS);
        map.put("BUILTIN_METHOD", AhkColors.AHK_BUILTIN_METHOD);
        map.put("BUILTIN_PROPERTY", AhkColors.AHK_BUILTIN_PROPERTY);
        map.put("FLOW", AhkColors.AHK_FLOWCONTROL);
        map.put("ESC", AhkColors.AHK_STRING_ESCAPE);
        map.put("DIRECTIVE", AhkColors.AHK_DIRECTIVE);
        map.put("RESERVED", AhkColors.AHK_RESERVED);
        map.put("HOTKEY", AhkColors.AHK_HOTKEY);
        map.put("HOTSTRING", AhkColors.AHK_HOTSTRING);
        map.put("DECLARED", AhkColors.AHK_DECLARED_TYPE);
        map.put("PRIVATE", AhkColors.AHK_PRIVATE_MEMBER);
        map.put("LABEL", AhkColors.AHK_LABEL);
        map.put("CLASS", AhkColors.AHK_CLASS);
        map.put("FUNC", AhkColors.AHK_FUNC);
        map.put("IGNORED", AhkColors.AHK_IGNORED_LINE);
//        map.put("", AhkColors.);
//        map.put("", AhkColors.);
        return map;
    }

    /** The configurable colors. */
    @Override
    @NotNull
    public AttributesDescriptor @NotNull [] getAttributeDescriptors() {
        AttributesDescriptor[] result = new AttributesDescriptor[Tables.COLORS.length];
        for (int i = 0; i < result.length; i++) {
            result[i] = new AttributesDescriptor(AhkBundle.message("color." + Tables.COLORS[i][0]),
                    (TextAttributesKey) Tables.COLORS[i][1]);
        }
        return result;
    }

    /** No plain colors (only text attributes). */
    @Override
    @NotNull
    public ColorDescriptor @NotNull [] getColorDescriptors() {
        return ColorDescriptor.EMPTY_ARRAY;
    }

    /** Name of the page in the settings. */
    @Override
    @NotNull
    public String getDisplayName() {
        return "AutoHotkey"; // name of the tab in the settings
    }
}
