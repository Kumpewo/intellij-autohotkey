package de.kumpewo.autohotkey.inspection;

import com.intellij.codeInspection.LocalInspectionTool;
import com.intellij.codeInspection.ProblemHighlightType;
import com.intellij.codeInspection.ProblemsHolder;
import com.intellij.psi.PsiElement;
import com.intellij.psi.PsiElementVisitor;
import com.intellij.psi.PsiFile;
import com.intellij.psi.PsiNamedElement;
import com.intellij.psi.PsiReference;
import com.intellij.psi.ResolveResult;
import com.intellij.psi.util.CachedValueProvider;
import com.intellij.psi.util.CachedValuesManager;
import com.intellij.psi.util.PsiModificationTracker;
import com.intellij.psi.util.PsiTreeUtil;
import de.kumpewo.autohotkey.AhkBundle;
import de.kumpewo.autohotkey.documentation.AhkDocComment;
import de.kumpewo.autohotkey.include.AhkInclude;
import de.kumpewo.autohotkey.navigation.AhkDefinitionReference;
import de.kumpewo.autohotkey.navigation.AhkFieldTarget;
import de.kumpewo.autohotkey.psi.AhkClass;
import de.kumpewo.autohotkey.psi.AhkClassProperty;
import de.kumpewo.autohotkey.psi.AhkDefinitions;
import de.kumpewo.autohotkey.psi.AhkFunc;
import de.kumpewo.autohotkey.psi.AhkTypes;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Inspection "Use of a deprecated definition": a name whose matching definitions (as for Ctrl+B, by name) are all
 * functions, methods, classes, properties or fields marked {@code @deprecated} in their doc comment
 * ({@link AhkDocComment}) is struck out, with the first line of the {@code @deprecated} text as message. Only names
 * that some deprecated definition of the file or the files it includes has are resolved. As an inspection it
 * also runs in <i>Code &gt; Inspect Code</i> for a whole project, and its level can be changed in the settings.
 */
public class AhkDeprecatedInspection extends LocalInspectionTool {

    /** Checks every identifier. */
    @Override
    public @NotNull PsiElementVisitor buildVisitor(@NotNull ProblemsHolder holder, boolean isOnTheFly) {
        return new PsiElementVisitor() {
            @Override
            public void visitElement(@NotNull PsiElement element) {
                if (element.getNode() == null || element.getNode().getElementType() != AhkTypes.IDENTIFIER) return;
                String message = message(element);
                if (message != null) holder.registerProblem(element, message, ProblemHighlightType.LIKE_DEPRECATED);
            }
        };
    }

    /**
     * The message for a name whose matching definitions are all deprecated, otherwise {@code null}. The name of a
     * definition itself is not marked.
     */
    static @Nullable String message(@NotNull PsiElement identifier) {
        PsiFile file = identifier.getContainingFile();
        PsiElement parent = identifier.getParent();
        if (file == null || parent == null) return null;
        if (!deprecatedNames(file).contains(identifier.getText().toLowerCase(Locale.ROOT))) return null;
        int offset = identifier.getTextRange().getStartOffset();
        for (PsiReference reference : parent.getReferences()) {
            if (!(reference instanceof AhkDefinitionReference definitionReference)
                    || !reference.getAbsoluteRange().containsOffset(offset)) continue;
            ResolveResult[] results = definitionReference.multiResolve(false);
            if (results.length == 0) return null;
            String text = null;
            for (ResolveResult result : results) {
                PsiElement target = result.getElement();
                if (isDefinitionName(target, identifier)) return null;
                AhkDocComment doc = AhkDocComment.of(target);
                if (doc == null || doc.deprecated() == null) return null;
                if (text == null || text.isEmpty()) text = doc.deprecated();
            }
            String firstLine = text == null ? "" : text.lines().findFirst().orElse("").strip();
            return firstLine.isEmpty()
                    ? AhkBundle.message("doc.deprecated", identifier.getText())
                    : AhkBundle.message("doc.deprecated.text", identifier.getText(), firstLine);
        }
        return null;
    }

    /** Is the identifier the name of the target itself (the definition, not a use of it)? */
    private static boolean isDefinitionName(@Nullable PsiElement target, PsiElement identifier) {
        if (target instanceof AhkFieldTarget field) return field.identifier() == identifier;
        return target instanceof com.intellij.psi.PsiNameIdentifierOwner owner && owner.getNameIdentifier() == identifier;
    }

    /**
     * Names (lower case) of the deprecated functions, methods, classes, properties and fields in the file and the
     * files it includes, recomputed on every PSI change.
     */
    private static Set<String> deprecatedNames(PsiFile file) {
        return CachedValuesManager.getCachedValue(file, () -> {
            Set<String> names = new HashSet<>();
            List<PsiFile> files = new ArrayList<>();
            files.add(file);
            files.addAll(AhkInclude.visibleFiles(file));
            for (PsiFile source : files) {
                for (PsiElement definition : PsiTreeUtil.findChildrenOfAnyType(source,
                        AhkFunc.class, AhkClass.class, AhkClassProperty.class)) {
                    addIfDeprecated(definition, ((PsiNamedElement) definition).getName(), names);
                }
                for (AhkClass cls : PsiTreeUtil.findChildrenOfType(source, AhkClass.class)) {
                    for (AhkDefinitions.Field field : AhkDefinitions.fields(cls)) {
                        addIfDeprecated(new AhkFieldTarget(field), field.name(), names);
                    }
                }
            }
            return CachedValueProvider.Result.create(Set.copyOf(names), PsiModificationTracker.getInstance(file.getProject()));
        });
    }

    /** Adds the name (lower case) if the definition is marked {@code @deprecated}. */
    private static void addIfDeprecated(PsiElement definition, @Nullable String name, Set<String> names) {
        AhkDocComment doc = AhkDocComment.of(definition);
        if (doc != null && doc.deprecated() != null && name != null) names.add(name.toLowerCase(Locale.ROOT));
    }
}
