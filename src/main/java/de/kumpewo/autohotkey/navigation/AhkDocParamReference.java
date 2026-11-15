package de.kumpewo.autohotkey.navigation;

import com.intellij.openapi.util.TextRange;
import com.intellij.psi.PsiElement;
import com.intellij.psi.PsiFile;
import com.intellij.psi.PsiReferenceBase;
import com.intellij.psi.util.PsiTreeUtil;
import de.kumpewo.autohotkey.documentation.AhkDocComment;
import de.kumpewo.autohotkey.psi.AhkFunc;
import de.kumpewo.autohotkey.psi.AhkLocals;
import de.kumpewo.autohotkey.psi.AhkTypes;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;

/**
 * Reference from the name after {@code @param} in the doc comment of a function to its parameter
 * ({@link AhkLocalVariable}), so that renaming a parameter also renames it in the doc comment, and Find Usages and the
 * highlighting of occurrences show it. The doc comment consists of several BLOCK_COMMENT leaves; the reference lies in
 * the leaf that contains the name. {@code @param} names without a parameter of that name refer to nothing.
 */
public final class AhkDocParamReference extends PsiReferenceBase<PsiElement> {

    private final AhkFunc function;

    /** Reference in the comment leaf for the range of the name inside it. */
    private AhkDocParamReference(@NotNull PsiElement comment, @NotNull TextRange range, @NotNull AhkFunc function) {
        super(comment, range, true);
        this.function = function;
    }

    /** The references of one comment leaf: the {@code @param} names in it, if the comment documents a function. */
    public static @NotNull List<AhkDocParamReference> in(@NotNull PsiElement comment) {
        List<AhkDocParamReference> result = new ArrayList<>();
        if (comment.getNode().getElementType() != AhkTypes.BLOCK_COMMENT) return result;
        PsiElement end = comment;
        while (!end.getText().endsWith("*/")) {
            PsiElement next = PsiTreeUtil.nextLeaf(end);
            if (next == null || next.getNode().getElementType() != AhkTypes.BLOCK_COMMENT) return result;
            end = next;
        }
        if (!(AhkDocComment.documentedDefinition(end) instanceof AhkFunc function)) return result;
        AhkDocComment doc = AhkDocComment.of(function);
        if (doc == null) return result;
        TextRange leafRange = comment.getTextRange();
        for (AhkDocComment.Param param : doc.params()) {
            TextRange range = param.nameRange();
            if (leafRange.contains(range)) {
                result.add(new AhkDocParamReference(comment, range.shiftLeft(leafRange.getStartOffset()), function));
            }
        }
        return result;
    }

    /** The references to the parameter in the doc comment of its function (empty for other variables). */
    public static @NotNull List<AhkDocParamReference> of(@NotNull AhkLocals.Local local) {
        List<AhkDocParamReference> result = new ArrayList<>();
        if (!local.parameter() || !(local.scope() instanceof AhkFunc function)) return result;
        AhkDocComment doc = AhkDocComment.of(function);
        PsiFile file = function.getContainingFile();
        if (doc == null || file == null) return result;
        for (AhkDocComment.Param param : doc.params()) {
            if (!param.name().equalsIgnoreCase(local.key())) continue;
            PsiElement leaf = file.findElementAt(param.nameRange().getStartOffset());
            if (leaf == null) continue;
            for (AhkDocParamReference reference : in(leaf)) {
                if (reference.getAbsoluteRange().equals(param.nameRange())) result.add(reference);
            }
        }
        return result;
    }

    /** The parameter of the documented function with this name, or {@code null}. */
    @Override
    public @Nullable PsiElement resolve() {
        if (!function.isValid()) return null;
        AhkLocals.Local local = AhkLocals.resolve(getValue(), function);
        return local != null && local.parameter() && local.scope() == function ? new AhkLocalVariable(local) : null;
    }

    /** Same parameter (targets are new objects, compared with equals). */
    @Override
    public boolean isReferenceTo(@NotNull PsiElement element) {
        return element instanceof AhkLocalVariable && element.equals(resolve());
    }

    /** Rename: replaces the name in the comment leaf. */
    @Override
    public PsiElement handleElementRename(@NotNull String newElementName) {
        return de.kumpewo.autohotkey.psi.AhkRenames.replaceRange(myElement, getRangeInElement(), newElementName);
    }

    /** No variants. */
    @Override
    public Object @NotNull [] getVariants() {
        return EMPTY_ARRAY;
    }
}
