package de.kumpewo.autohotkey.annotation;

import com.intellij.codeInsight.highlighting.HighlightErrorFilter;
import com.intellij.psi.PsiErrorElement;
import de.kumpewo.autohotkey.AhkLanguage;
import org.jetbrains.annotations.NotNull;

/** Hides parser errors that start in a line marked with ";@ignore-next-line" (AhkIgnoredLines). */
public class AhkIgnoredLineErrorFilter extends HighlightErrorFilter {

    /** False for AutoHotkey parser errors in a marked line. */
    @Override
    public boolean shouldHighlightErrorElement(@NotNull PsiErrorElement element) {
        if (element.getLanguage() != AhkLanguage.INSTANCE) return true;
        return !AhkIgnoredLines.isIgnored(element);
    }
}
