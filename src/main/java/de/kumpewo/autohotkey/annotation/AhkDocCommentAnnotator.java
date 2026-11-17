package de.kumpewo.autohotkey.annotation;

import com.intellij.codeInspection.ProblemHighlightType;
import com.intellij.lang.annotation.AnnotationHolder;
import com.intellij.lang.annotation.Annotator;
import com.intellij.lang.annotation.HighlightSeverity;
import com.intellij.psi.PsiElement;
import com.intellij.psi.PsiFile;
import com.intellij.psi.PsiReference;
import com.intellij.psi.ResolveResult;
import com.intellij.psi.util.CachedValueProvider;
import com.intellij.psi.util.CachedValuesManager;
import com.intellij.psi.util.PsiModificationTracker;
import com.intellij.psi.util.PsiTreeUtil;
import de.kumpewo.autohotkey.AhkBundle;
import de.kumpewo.autohotkey.AhkFile;
import de.kumpewo.autohotkey.documentation.AhkDocComment;
import de.kumpewo.autohotkey.include.AhkInclude;
import de.kumpewo.autohotkey.navigation.AhkDefinitionReference;
import de.kumpewo.autohotkey.psi.AhkClass;
import de.kumpewo.autohotkey.psi.AhkFunc;
import de.kumpewo.autohotkey.psi.AhkTypes;
import de.kumpewo.autohotkey.types.AhkTypeDeclarations;
import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Doc comments ({@link AhkDocComment}):
 * <ul>
 *   <li>on the file (the ranges lie in comments, outside of the definitions): warning "Unknown parameter 'x'." for
 *       {@code @param x} without a parameter {@code x}, "Unknown class." for an unknown type in {@code {...}};</li>
 *   <li>on a name that refers to own definitions: struck out if all matching definitions are marked
 *       {@code @deprecated} (like Ctrl+B, by name).</li>
 * </ul>
 */
public class AhkDocCommentAnnotator implements Annotator {

    /** Checks the doc comments of the file, or a name for deprecated targets. */
    @Override
    public void annotate(@NotNull PsiElement element, @NotNull AnnotationHolder holder) {
        if (element instanceof AhkFile file) {
            checkComments(file, holder);
        } else if (element.getNode().getElementType() == AhkTypes.IDENTIFIER) {
            checkDeprecated(element, holder);
        }
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

    /**
     * Strikes out a name whose matching definitions (as for Ctrl+B) are all functions, methods or classes marked
     * {@code @deprecated}. Only names that some deprecated definition of the file or its direct #Include files has
     * are resolved.
     */
    private static void checkDeprecated(PsiElement identifier, AnnotationHolder holder) {
        PsiFile file = identifier.getContainingFile();
        PsiElement parent = identifier.getParent();
        if (file == null || parent == null) return;
        if (!deprecatedNames(file).contains(identifier.getText().toLowerCase(Locale.ROOT))) return;
        int offset = identifier.getTextRange().getStartOffset();
        for (PsiReference reference : parent.getReferences()) {
            if (!(reference instanceof AhkDefinitionReference definitionReference)
                    || !reference.getAbsoluteRange().containsOffset(offset)) continue;
            ResolveResult[] results = definitionReference.multiResolve(false);
            if (results.length == 0) return;
            String text = null;
            for (ResolveResult result : results) {
                AhkDocComment doc = AhkDocComment.of(result.getElement());
                if (doc == null || doc.deprecated() == null) return;
                if (text == null || text.isEmpty()) text = doc.deprecated();
            }
            String firstLine = text == null ? "" : text.lines().findFirst().orElse("").strip();
            String message = firstLine.isEmpty()
                    ? AhkBundle.message("doc.deprecated", identifier.getText())
                    : AhkBundle.message("doc.deprecated.text", identifier.getText(), firstLine);
            holder.newAnnotation(HighlightSeverity.WARNING, message).range(identifier)
                    .highlightType(ProblemHighlightType.LIKE_DEPRECATED).create();
            return;
        }
    }

    /**
     * Names (lower case) of the deprecated functions, methods and classes in the file and its direct #Include files,
     * recomputed on every PSI change.
     */
    private static Set<String> deprecatedNames(PsiFile file) {
        return CachedValuesManager.getCachedValue(file, () -> {
            Set<String> names = new HashSet<>();
            List<PsiFile> files = new ArrayList<>();
            files.add(file);
            files.addAll(AhkInclude.includedFiles(file));
            for (PsiFile source : files) {
                for (PsiElement definition : PsiTreeUtil.findChildrenOfAnyType(source, AhkFunc.class, AhkClass.class)) {
                    AhkDocComment doc = AhkDocComment.of(definition);
                    String name = ((com.intellij.psi.PsiNamedElement) definition).getName();
                    if (doc != null && doc.deprecated() != null && name != null) names.add(name.toLowerCase(Locale.ROOT));
                }
            }
            return CachedValueProvider.Result.create(Set.copyOf(names), PsiModificationTracker.getInstance(file.getProject()));
        });
    }
}
