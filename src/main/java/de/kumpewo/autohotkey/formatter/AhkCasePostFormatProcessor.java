package de.kumpewo.autohotkey.formatter;

import com.intellij.openapi.editor.colors.TextAttributesKey;
import com.intellij.openapi.util.TextRange;
import com.intellij.psi.PsiElement;
import com.intellij.psi.PsiFile;
import com.intellij.psi.codeStyle.CodeStyleSettings;
import com.intellij.psi.impl.source.codeStyle.PostFormatProcessor;
import com.intellij.psi.impl.source.tree.LeafPsiElement;
import com.intellij.psi.tree.IElementType;
import com.intellij.psi.util.PsiTreeUtil;
import de.kumpewo.autohotkey.AhkLanguage;
import de.kumpewo.autohotkey.annotation.AhkAnnotator;
import de.kumpewo.autohotkey.annotation.AhkIgnoredLines;
import de.kumpewo.autohotkey.highlighting.AhkColors;
import de.kumpewo.autohotkey.highlighting.AhkNames;
import de.kumpewo.autohotkey.psi.AhkOtherVersionCode;
import de.kumpewo.autohotkey.psi.AhkTypes;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;

/**
 * "Reformat Code" adjusts the case of known words (the formatter itself may only change whitespace, hence a
 * PostFormatProcessor; runs on the file or the selection, Ctrl+Z works).
 * <p>
 * Only words that are colored in their role (roles as in AhkAnnotator):
 * <ul>
 *   <li>Keywords (also "default" as a switch branch and Files/Parse/Read/Reg after Loop): spelling from
 *       keyword-case.txt according to the switch of their group; words in no group stay.</li>
 *   <li>Built-in variables, functions, classes, nested classes, methods, properties and directives: spelling of
 *       the name list that provides the color (switch ADJUST_BUILTIN_CASE).</li>
 * </ul>
 * Never: strings, comments, hotstrings, own and declared names, definitions, object keys, keywords after the
 * dot, the line after ;@ignore-next-line, OTHER_VERSION_CODE. Main switch ADJUST_CASE (default off).
 */
public final class AhkCasePostFormatProcessor implements PostFormatProcessor {

    /** Adjusts the case inside an element (PSI-based formatting). */
    @Override
    public @NotNull PsiElement processElement(@NotNull PsiElement source, @NotNull CodeStyleSettings settings) {
        if (source.getLanguage() == AhkLanguage.INSTANCE) adjust(source, source.getTextRange(), settings);
        return source;
    }

    /** Adjusts the case inside a text range (the action "Reformat Code"). */
    @Override
    public @NotNull TextRange processText(@NotNull PsiFile source, @NotNull TextRange range,
                                          @NotNull CodeStyleSettings settings) {
        if (source.getLanguage() == AhkLanguage.INSTANCE) adjust(source, range, settings);
        return range;   // same length, the range stays
    }

    /** Collects the leaves to change first, then replaces them (the tree must not change while walking it). */
    private static void adjust(PsiElement root, TextRange range, CodeStyleSettings settings) {
        AhkCodeStyleSettings custom = settings.getCustomSettings(AhkCodeStyleSettings.class);
        if (!custom.ADJUST_CASE) return;
        List<LeafPsiElement> leaves = new ArrayList<>();
        List<String> texts = new ArrayList<>();
        PsiTreeUtil.processElements(root, element -> {
            if (element instanceof LeafPsiElement leaf && range.contains(leaf.getTextRange())) {
                String text = adjustedText(leaf, custom);
                if (text != null && !text.equals(leaf.getText())) {
                    leaves.add(leaf);
                    texts.add(text);
                }
            }
            return true;
        });
        for (int i = 0; i < leaves.size(); i++) {
            if (leaves.get(i).isValid()) leaves.get(i).replaceWithText(texts.get(i));
        }
    }

    /** New spelling of the leaf, null = don't change. Same word, only a different case. */
    static @Nullable String adjustedText(@NotNull PsiElement leaf, @NotNull AhkCodeStyleSettings settings) {
        IElementType type = leaf.getNode().getElementType();
        if (type != AhkTypes.IDENTIFIER && type != AhkTypes.DIRECTIVE_NAME && AhkAnnotator.keywordColor(leaf) == null) {
            return null;   // fast exit for operators, strings, comments ...
        }
        if (PsiTreeUtil.getParentOfType(leaf, AhkOtherVersionCode.class) != null) return null;
        if (AhkIgnoredLines.isIgnored(leaf)) return null;
        String text = leaf.getText();
        String result = spelling(leaf, type, text, settings);
        // only the case may change (protection against wrong list entries)
        return result != null && result.equalsIgnoreCase(text) ? result : null;
    }

    /** Spelling by the role of the leaf (keyword group or name list of its color). */
    private static @Nullable String spelling(PsiElement leaf, IElementType type, String text,
                                             AhkCodeStyleSettings settings) {
        AhkNames names = AhkNames.of(leaf);
        if (type == AhkTypes.DIRECTIVE_NAME) {
            return settings.ADJUST_BUILTIN_CASE ? names.spelling(text, "directives") : null;
        }
        if (!AhkAnnotator.isName(leaf)) {
            return AhkKeywordCase.spelling(text, settings);   // keyword token (keywordColor != null)
        }
        TextAttributesKey color = AhkAnnotator.identifierColor(leaf);
        if (color == null) return null;
        if (color == AhkColors.AHK_FLOWCONTROL) return AhkKeywordCase.spelling(text, settings);   // default, Files ...
        if (!settings.ADJUST_BUILTIN_CASE) return null;
        if (color == AhkColors.AHK_BUILTIN_VARIABLE) return names.spelling(text, "variables");
        if (color == AhkColors.AHK_BUILTIN_FUNCTION) return names.spelling(text, "functions");
        if (color == AhkColors.AHK_BUILTIN_METHOD) return names.spelling(text, "methods");
        if (color == AhkColors.AHK_BUILTIN_PROPERTY) return names.spelling(text, "properties");
        if (color == AhkColors.AHK_BUILTIN_CLASS) {
            // after the dot (Gui.Button, extends Gui.Edit): nested class of the class before it
            PsiElement dot = PsiTreeUtil.prevVisibleLeaf(leaf);
            if (dot != null && dot.getNode().getElementType() == AhkTypes.DOT) {
                PsiElement outer = PsiTreeUtil.prevVisibleLeaf(dot);
                if (outer == null) return null;
                String nested = names.spelling(outer.getText() + "." + text, "nested-classes");
                return nested == null ? null : nested.substring(nested.indexOf('.') + 1);
            }
            return names.spelling(text, "classes");
        }
        return null;   // AHK_DECLARED_TYPE: own name
    }
}
