package de.kumpewo.autohotkey.annotation;

import de.kumpewo.autohotkey.AhkBundle;
import com.intellij.lang.ASTNode;
import com.intellij.lang.annotation.AnnotationHolder;
import com.intellij.lang.annotation.Annotator;
import com.intellij.lang.annotation.HighlightSeverity;
import com.intellij.psi.PsiElement;
import com.intellij.psi.TokenType;
import com.intellij.psi.tree.IElementType;
import de.kumpewo.autohotkey.psi.AhkTypes;
import org.jetbrains.annotations.NotNull;

/**
 * After the opening brace of a block nothing else may follow on the same line (only a comment), except an
 * immediate "}" ("{}").
 * Deliberately an annotator and not part of the grammar: the rest of the block is still parsed normally and there
 * is exactly ONE error at the right place instead of follow-up errors.
 */
public class AhkBlockBraceAnnotator implements Annotator {

    /** Checks every element (see {@link #check}). */
    @Override
    public void annotate(@NotNull PsiElement element, @NotNull AnnotationHolder holder) {
        check(element, holder);
    }

    /**
     * The actual check.
     * Returns immediately if element is not a BLOCK or SWITCH_BLOCK (annotate() is called for every PSI element).
     */
    public static void check(@NotNull PsiElement element, @NotNull AnnotationHolder holder) {
        ASTNode block = element.getNode();
        if (block == null) return;
        if (block.getElementType() == AhkTypes.BLOCK) {
            // Only the body of a control structure (BODY) or definition (FUNC, CLASS). Object literals are OBJECT_EXPR
            // and therefore not a BLOCK at all: d := { Buttons: "Yes|No" } on one line is fine.
            ASTNode parent = block.getTreeParent();
            IElementType parentType = parent == null ? null : parent.getElementType();
            if (parentType != AhkTypes.BODY && parentType != AhkTypes.FUNC && parentType != AhkTypes.CLASS
                    && parentType != AhkTypes.ACCESSOR) return;
        } else if (block.getElementType() != AhkTypes.SWITCH_BLOCK) {
            // switch x { case 1: ... - a line break must follow "{" here as well
            return;
        }

        ASTNode brace = block.getFirstChildNode();
        if (brace == null || brace.getElementType() != AhkTypes.L_BRACE) return;

        for (ASTNode n = brace.getTreeNext(); n != null; n = n.getTreeNext()) {
            IElementType t = n.getElementType();
            if (t == TokenType.WHITE_SPACE || t == AhkTypes.LINE_COMMENT || t == AhkTypes.BLOCK_COMMENT
                    || t == AhkTypes.BAD_COMMENT
                    || n.getTextLength() == 0) {
                continue;
            }
            if (t == AhkTypes.CRLF || t == AhkTypes.R_BRACE) return;   // ok: line break or "{}"
            holder.newAnnotation(HighlightSeverity.ERROR, AhkBundle.message("annotator.brace.line.break"))
                    .range(n)
                    .create();
            return;
        }
    }
}
