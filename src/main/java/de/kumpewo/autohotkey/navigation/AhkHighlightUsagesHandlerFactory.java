package de.kumpewo.autohotkey.navigation;

import com.intellij.codeInsight.highlighting.HighlightUsagesHandlerBase;
import com.intellij.codeInsight.highlighting.HighlightUsagesHandlerFactoryBase;
import com.intellij.lang.ASTNode;
import com.intellij.openapi.editor.Editor;
import com.intellij.openapi.project.DumbAware;
import com.intellij.psi.PsiElement;
import com.intellij.psi.PsiFile;
import com.intellij.psi.tree.IElementType;
import com.intellij.psi.util.PsiTreeUtil;
import com.intellij.util.Consumer;
import de.kumpewo.autohotkey.AhkFile;
import de.kumpewo.autohotkey.psi.AhkTypes;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.List;

/**
 * Highlights the occurrences of the name under the caret in the file (automatically while the caret rests on it, and
 * with Ctrl+Shift+F7). Without type information, an occurrence is every identifier with the same name, ignoring case
 * as AutoHotkey does: variables, functions, classes, members after the dot, labels. Definitions and assignment targets
 * (function, class, property and accessor-less field names, parameters, {@code local}/{@code global}/{@code static}
 * declarations, {@code for} and {@code catch ... as} variables, labels, the left side of {@code :=} and other
 * assignment operators) are write occurrences, everything else read occurrences. Names in strings and comments don't
 * count. Accessor names ({@code get}/{@code set} in a property) are no target.
 */
public class AhkHighlightUsagesHandlerFactory extends HighlightUsagesHandlerFactoryBase implements DumbAware {

    /** Handler for an identifier in an AutoHotkey file, {@code null} elsewhere. */
    @Override
    public @Nullable HighlightUsagesHandlerBase<?> createHighlightUsagesHandler(@NotNull Editor editor,
                                                                                @NotNull PsiFile psiFile,
                                                                                @NotNull PsiElement target) {
        if (!(psiFile instanceof AhkFile) || !isName(target)) return null;
        return new Handler(editor, psiFile, target);
    }

    /** An identifier that names something (not an accessor name). */
    static boolean isName(@NotNull PsiElement leaf) {
        ASTNode node = leaf.getNode();
        if (node == null || node.getElementType() != AhkTypes.IDENTIFIER) return false;
        PsiElement parent = leaf.getParent();
        return parent == null || parent.getNode().getElementType() != AhkTypes.ACCESSOR;
    }

    /** Is the identifier a definition or the target of an assignment? */
    static boolean isWrite(@NotNull PsiElement identifier) {
        PsiElement parent = identifier.getParent();
        if (parent == null) return false;
        IElementType type = parent.getNode().getElementType();
        if (type == AhkTypes.PARAMETER || type == AhkTypes.DECL_ITEM || type == AhkTypes.FOR_VARS
                || type == AhkTypes.CATCH_CLAUSE || type == AhkTypes.LABEL) {
            return true;
        }
        if (type == AhkTypes.FUNC || type == AhkTypes.CLASS || type == AhkTypes.CLASS_PROPERTY) {
            // the name: the first identifier (for a class the names after "extends" are read occurrences)
            ASTNode first = parent.getNode().findChildByType(AhkTypes.IDENTIFIER);
            return first != null && first.getPsi() == identifier;
        }
        if (type == AhkTypes.REF_EXPR || type == AhkTypes.MEMBER_EXPR && isMemberName(identifier)) {
            // x := 1, obj.x += 1: the expression is the left operand of an assignment
            PsiElement expression = parent;
            PsiElement assignment = expression.getParent();
            if (assignment != null && assignment.getNode().getElementType() == AhkTypes.ASSIGN_EXPR
                    && assignment.getFirstChild() == expression) {
                return true;
            }
        }
        return false;
    }

    /** The identifier is the name after the dot of its member expression. */
    private static boolean isMemberName(PsiElement identifier) {
        return identifier.getParent().getLastChild() == identifier;
    }

    /** Collects all identifiers with the name of the target. */
    private static final class Handler extends HighlightUsagesHandlerBase<PsiElement> {
        private final PsiElement target;

        /** Handler for {@code target} in {@code file}. */
        Handler(@NotNull Editor editor, @NotNull PsiFile file, @NotNull PsiElement target) {
            super(editor, file);
            this.target = target;
        }

        /** The identifier under the caret. */
        @Override
        public @NotNull List<PsiElement> getTargets() {
            return List.of(target);
        }

        /** There is only one target. */
        @Override
        protected void selectTargets(@NotNull List<? extends PsiElement> targets,
                                     @NotNull Consumer<? super List<? extends PsiElement>> selectionConsumer) {
            selectionConsumer.consume(targets);
        }

        /** All identifiers of the file with the same name, ignoring case. */
        @Override
        public void computeUsages(@NotNull List<? extends PsiElement> targets) {
            String name = target.getText();
            for (PsiElement leaf = PsiTreeUtil.firstChild(myFile); leaf != null; leaf = PsiTreeUtil.nextLeaf(leaf)) {
                if (!isName(leaf) || !leaf.getText().equalsIgnoreCase(name)) continue;
                (isWrite(leaf) ? myWriteUsages : myReadUsages).add(leaf.getTextRange());
            }
            buildStatusText(name, myReadUsages.size() + myWriteUsages.size());
        }

        /** The platform does not search references in addition. */
        @Override
        public boolean highlightReferences() {
            return false;
        }
    }
}
