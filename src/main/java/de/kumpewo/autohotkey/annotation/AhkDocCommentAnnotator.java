package de.kumpewo.autohotkey.annotation;

import com.intellij.lang.annotation.AnnotationHolder;
import com.intellij.lang.annotation.Annotator;
import com.intellij.lang.annotation.HighlightSeverity;
import com.intellij.psi.PsiElement;
import com.intellij.psi.PsiFile;
import com.intellij.psi.util.PsiTreeUtil;
import de.kumpewo.autohotkey.AhkBundle;
import de.kumpewo.autohotkey.AhkFile;
import de.kumpewo.autohotkey.documentation.AhkDocComment;
import de.kumpewo.autohotkey.psi.AhkClass;
import de.kumpewo.autohotkey.psi.AhkFunc;
import de.kumpewo.autohotkey.types.AhkTypeDeclarations;
import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;
import java.util.List;

/**
 * Doc comments ({@link AhkDocComment}):
 * on the file (the ranges lie in comments, outside of the definitions): warning "Unknown parameter 'x'." for
 * {@code @param x} without a parameter {@code x}, "Unknown class." for an unknown type in {@code {...}}. Uses of
 * deprecated definitions: {@link de.kumpewo.autohotkey.inspection.AhkDeprecatedInspection}.
 */
public class AhkDocCommentAnnotator implements Annotator {

    /** Checks the doc comments of the file. */
    @Override
    public void annotate(@NotNull PsiElement element, @NotNull AnnotationHolder holder) {
        if (element instanceof AhkFile file) checkComments(file, holder);
    }

    /** Warnings for unknown parameters and classes in the doc comments of all functions, methods and classes. */
    private static void checkComments(PsiFile file, AnnotationHolder holder) {
        for (PsiElement definition : PsiTreeUtil.findChildrenOfAnyType(file, AhkFunc.class, AhkClass.class)) {
            AhkDocComment doc = AhkDocComment.of(definition);
            if (doc == null) continue;
            List<String> names = definition instanceof AhkFunc func ? AhkDocComment.parameterNames(func) : List.of();
            List<AhkDocComment.Type> types = new ArrayList<>();
            for (AhkDocComment.Param param : doc.params()) {
                if (names.stream().noneMatch(param.name()::equalsIgnoreCase)) {
                    holder.newAnnotation(HighlightSeverity.WARNING, AhkBundle.message("doc.unknown.param", param.name()))
                            .range(param.nameRange()).create();
                }
                types.addAll(param.types());
            }
            if (doc.returns() != null) types.addAll(doc.returns().types());
            for (AhkDocComment.Type type : types) {
                if (!AhkTypeDeclarations.isKnownClass(file, type.name())) {
                    holder.newAnnotation(HighlightSeverity.WARNING, AhkBundle.message("types.unknown.class"))
                            .range(type.range()).create();
                }
            }
        }
    }
}
