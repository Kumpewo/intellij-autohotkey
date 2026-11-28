package de.kumpewo.autohotkey.annotation;

import com.intellij.lang.annotation.AnnotationHolder;
import com.intellij.lang.annotation.Annotator;
import com.intellij.lang.annotation.HighlightSeverity;
import com.intellij.psi.PsiComment;
import com.intellij.psi.PsiElement;
import de.kumpewo.autohotkey.psi.AhkTypes;
import de.kumpewo.autohotkey.types.AhkTypeDeclarations;
import org.jetbrains.annotations.NotNull;

/**
 * Warnings in the pragmas ";@declare-type", ";@declare-auto-type", ";@declare-private" and ";@declare-member-type"
 * (AhkTypeDeclarations): unknown class, invalid pattern, missing "as", no member in the next line ... The faulty part
 * of the comment is marked.
 */
public class AhkTypeDeclarationAnnotator implements Annotator {

    /** Reports the problems of a line comment that is a type pragma. */
    @Override
    public void annotate(@NotNull PsiElement element, @NotNull AnnotationHolder holder) {
        if (!(element instanceof PsiComment) || element.getNode().getElementType() != AhkTypes.LINE_COMMENT) return;
        for (AhkTypeDeclarations.Problem problem : AhkTypeDeclarations.problems((PsiComment) element)) {
            holder.newAnnotation(HighlightSeverity.WARNING, problem.message())
                    .range(problem.range())
                    .create();
        }
    }
}
