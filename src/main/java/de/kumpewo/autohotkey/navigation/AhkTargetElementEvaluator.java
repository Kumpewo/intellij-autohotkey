package de.kumpewo.autohotkey.navigation;

import com.intellij.codeInsight.TargetElementEvaluatorEx2;
import com.intellij.psi.PsiElement;
import de.kumpewo.autohotkey.module.AhkModuleSymbol;
import de.kumpewo.autohotkey.module.AhkModules;
import de.kumpewo.autohotkey.psi.AhkDefinitions;
import de.kumpewo.autohotkey.psi.AhkDirective;
import de.kumpewo.autohotkey.psi.AhkImportItem;
import de.kumpewo.autohotkey.psi.AhkImportSpec;
import de.kumpewo.autohotkey.psi.AhkTypes;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * Target at the caret for names that are no named PSI elements, at their definition; without this the platform
 * finds nothing to search or rename there:
 * <ul>
 *   <li>the name of a field in its defining statement ({@code count := 0}, {@code static count := 0}, a typed
 *       property {@code left : Int32}, also inside a {@code union} block): the same {@link AhkFieldTarget} that a
 *       name after the dot resolves to;</li>
 *   <li>v2.1 modules: the name in {@code #Module Name} and an alias after {@code as} in {@code #Import}
 *       ({@link AhkModuleSymbol}).</li>
 * </ul>
 */
public final class AhkTargetElementEvaluator extends TargetElementEvaluatorEx2 {

    /** The field or module target if the leaf is such a name in its definition, otherwise {@code null}. */
    @Override
    public @Nullable PsiElement getNamedElement(@NotNull PsiElement element) {
        if (element.getNode() == null) return null;
        if (element.getNode().getElementType() == AhkTypes.DIRECTIVE_ARGS
                && element.getParent() instanceof AhkDirective directive && AhkModules.isModuleDirective(directive)
                && AhkModules.isActive(element.getContainingFile())) {
            return AhkModules.moduleSymbol(directive);
        }
        if (element.getNode().getElementType() != AhkTypes.IDENTIFIER) return null;
        if (isAlias(element)) return new AhkModuleSymbol(AhkModuleSymbol.Kind.ALIAS, element);
        AhkDefinitions.Field field = AhkDefinitions.field(element);
        return field == null ? null : new AhkFieldTarget(field);
    }

    /** Identifier after {@code as} in {@code #Import X as Alias} or {@code #Import X {Name as Alias}}. */
    private static boolean isAlias(@NotNull PsiElement identifier) {
        PsiElement parent = identifier.getParent();
        if (!(parent instanceof AhkImportSpec) && !(parent instanceof AhkImportItem)) return false;
        PsiElement previous = com.intellij.psi.util.PsiTreeUtil.skipWhitespacesAndCommentsBackward(identifier);
        return previous != null && previous.getNode().getElementType() == AhkTypes.KEYWORD_AS;
    }
}
