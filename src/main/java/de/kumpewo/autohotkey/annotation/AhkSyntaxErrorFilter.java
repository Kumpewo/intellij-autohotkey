package de.kumpewo.autohotkey.annotation;

import com.intellij.codeInsight.highlighting.HighlightErrorFilter;
import com.intellij.psi.PsiErrorElement;
import de.kumpewo.autohotkey.AhkLanguage;
import org.jetbrains.annotations.NotNull;

/**
 * Hides the technical parser errors of Grammar-Kit; AhkSyntaxErrorAnnotator shows them as "Syntax error"
 * instead. The plugin's own parser messages stay visible.
 */
public class AhkSyntaxErrorFilter extends HighlightErrorFilter {

    /** False for technical AutoHotkey parser errors. */
    @Override
    public boolean shouldHighlightErrorElement(@NotNull PsiErrorElement element) {
        if (element.getLanguage() != AhkLanguage.INSTANCE) return true;
        return !AhkSyntaxErrorAnnotator.isTechnical(element);
    }
}
