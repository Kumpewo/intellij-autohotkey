package de.kumpewo.autohotkey.annotation;

import com.intellij.lang.ASTNode;
import com.intellij.lang.annotation.AnnotationHolder;
import com.intellij.lang.annotation.Annotator;
import com.intellij.lang.annotation.HighlightSeverity;
import com.intellij.psi.PsiElement;
import de.kumpewo.autohotkey.AhkBundle;
import de.kumpewo.autohotkey.parser.AhkLanguageLevel;
import de.kumpewo.autohotkey.psi.AhkParameter;
import de.kumpewo.autohotkey.psi.AhkTypes;
import org.jetbrains.annotations.NotNull;

/**
 * Mandatory parameter after an optional one ({@code f(a?, b)}, {@code f(a := 1, &b)}, {@code P[a?, b]}): allowed
 * only in AutoHotkey v2.1, AutoHotkey v2.0 reports "Parameter default required". The syntax is the same at both
 * levels, so the parser accepts it and this annotator reports it in files that are not v2.1. A variadic parameter
 * ({@code b*}, {@code *}) may always follow. The order is checked in every parameter list (functions, methods,
 * fat arrow functions, parameters of properties).
 */
public class AhkParameterOrderAnnotator implements Annotator {

    /** Reports a mandatory parameter that follows an optional one, outside of v2.1 files. */
    @Override
    public void annotate(@NotNull PsiElement element, @NotNull AnnotationHolder holder) {
        if (!(element instanceof AhkParameter parameter) || !isMandatory(parameter.getNode())) return;
        boolean afterOptional = false;
        for (ASTNode prev = parameter.getNode().getTreePrev(); prev != null && !afterOptional; prev = prev.getTreePrev()) {
            afterOptional = prev.getElementType() == AhkTypes.PARAMETER && isOptional(prev);
        }
        if (!afterOptional || AhkLanguageLevel.of(element) == AhkLanguageLevel.V2_1) return;
        holder.newAnnotation(HighlightSeverity.ERROR, AhkBundle.message("annotator.parameter.default.required"))
                .range(parameter)
                .create();
    }

    /** Optional parameter: {@code a?} or {@code a := value}. */
    private static boolean isOptional(@NotNull ASTNode parameter) {
        return parameter.findChildByType(AhkTypes.QUESTION) != null
                || parameter.findChildByType(AhkTypes.ASSIGN_OP) != null;
    }

    /** Neither optional nor variadic ({@code a}, {@code &a}). */
    private static boolean isMandatory(@NotNull ASTNode parameter) {
        return !isOptional(parameter) && parameter.findChildByType(AhkTypes.STAR) == null;
    }
}
