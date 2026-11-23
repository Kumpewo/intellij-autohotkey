package de.kumpewo.autohotkey.inspection;

import com.intellij.codeInspection.LocalInspectionTool;
import com.intellij.codeInspection.LocalQuickFix;
import com.intellij.codeInspection.ProblemDescriptor;
import com.intellij.codeInspection.ProblemHighlightType;
import com.intellij.codeInspection.ProblemsHolder;
import com.intellij.openapi.editor.Document;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.util.TextRange;
import com.intellij.psi.PsiDocumentManager;
import com.intellij.psi.PsiElement;
import com.intellij.psi.PsiElementVisitor;
import com.intellij.psi.PsiFile;
import com.intellij.util.text.EditDistance;
import de.kumpewo.autohotkey.AhkBundle;
import de.kumpewo.autohotkey.documentation.AhkDocComment;
import de.kumpewo.autohotkey.navigation.AhkFieldTarget;
import de.kumpewo.autohotkey.psi.AhkClass;
import de.kumpewo.autohotkey.psi.AhkClassProperty;
import de.kumpewo.autohotkey.psi.AhkDefinitions;
import de.kumpewo.autohotkey.psi.AhkFunc;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Inspection "Unknown doc comment tag": a tag at the start of a line of a doc comment ({@link AhkDocComment}) that is
 * neither one with a meaning ({@code @param}, {@code @returns} ...) nor a tolerated JSDoc tag ({@code @author} ...),
 * usually a typo such as {@code @retruns}. A word with {@code @} inside the text ({@code name@example.com}) is not a
 * tag. Quick fix: change it to the most similar tag, if one is close enough.
 */
public class AhkUnknownDocTagInspection extends LocalInspectionTool {

    /** Largest edit distance (with swapped neighbours as one step) for the suggestion of the quick fix. */
    private static final int MAX_DISTANCE = 2;

    /**
     * Checks the doc comments of functions, methods, classes, properties and fields; a comment in front of a field
     * statement with several names is checked once.
     */
    @Override
    public @NotNull PsiElementVisitor buildVisitor(@NotNull ProblemsHolder holder, boolean isOnTheFly) {
        Set<Integer> checked = new HashSet<>();
        return new PsiElementVisitor() {
            @Override
            public void visitElement(@NotNull PsiElement element) {
                if (element instanceof AhkFunc || element instanceof AhkClassProperty) {
                    check(element, holder, checked);
                } else if (element instanceof AhkClass cls) {
                    check(cls, holder, checked);
                    for (AhkDefinitions.Field field : AhkDefinitions.fields(cls)) {
                        check(new AhkFieldTarget(field), holder, checked);
                    }
                }
            }
        };
    }

    /** Reports the unknown tags of the doc comment of one definition. */
    private static void check(PsiElement definition, ProblemsHolder holder, Set<Integer> checked) {
        AhkDocComment doc = AhkDocComment.of(definition);
        if (doc == null) return;
        PsiFile file = holder.getFile();
        for (AhkDocComment.UnknownTag tag : doc.unknownTags()) {
            if (!checked.add(tag.range().getStartOffset())) continue;
            // the lexer splits a block comment only at "*", so "@name" lies in one token
            PsiElement leaf = file.findElementAt(tag.range().getStartOffset());
            if (leaf == null || !leaf.getTextRange().contains(tag.range())) continue;
            String suggestion = suggestion(tag.name());
            LocalQuickFix[] fixes = suggestion == null ? LocalQuickFix.EMPTY_ARRAY
                    : new LocalQuickFix[]{new ChangeTagFix(suggestion)};
            holder.registerProblem(leaf, AhkBundle.message("inspection.unknown.tag.message", tag.name()),
                    ProblemHighlightType.GENERIC_ERROR_OR_WARNING,
                    tag.range().shiftLeft(leaf.getTextRange().getStartOffset()), fixes);
        }
    }

    /**
     * The known or tolerated tag most similar to {@code name}, or {@code null} if none is within
     * {@link #MAX_DISTANCE} (and closer than the length of the name, so that short words get no wild guess).
     */
    public static @Nullable String suggestion(@NotNull String name) {
        List<String> candidates = new ArrayList<>(AhkDocComment.TAGS);
        candidates.addAll(AhkDocComment.TOLERATED_TAGS);
        String best = null;
        int bestDistance = Integer.MAX_VALUE;
        for (String candidate : candidates) {
            int distance = EditDistance.optimalAlignment(name, candidate, false);
            if (distance < bestDistance) {
                best = candidate;
                bestDistance = distance;
            }
        }
        return bestDistance <= MAX_DISTANCE && bestDistance < name.length() ? best : null;
    }

    /** Quick fix: replaces {@code @name} by {@code @tag}. */
    private static final class ChangeTagFix implements LocalQuickFix {
        private final String tag;

        ChangeTagFix(String tag) {
            this.tag = tag;
        }

        /** "Change to @returns". */
        @Override
        public @NotNull String getName() {
            return AhkBundle.message("inspection.unknown.tag.fix", tag);
        }

        /** Name of the group of fixes. */
        @Override
        public @NotNull String getFamilyName() {
            return AhkBundle.message("inspection.unknown.tag.fix.family");
        }

        /** Replaces the text in the reported range. */
        @Override
        public void applyFix(@NotNull Project project, @NotNull ProblemDescriptor descriptor) {
            PsiElement element = descriptor.getPsiElement();
            TextRange range = descriptor.getTextRangeInElement();
            if (element == null || range == null) return;
            PsiFile file = element.getContainingFile();
            Document document = file == null ? null : PsiDocumentManager.getInstance(project).getDocument(file);
            if (document == null) return;
            TextRange absolute = range.shiftRight(element.getTextRange().getStartOffset());
            document.replaceString(absolute.getStartOffset(), absolute.getEndOffset(), "@" + tag);
            PsiDocumentManager.getInstance(project).commitDocument(document);
        }
    }
}
