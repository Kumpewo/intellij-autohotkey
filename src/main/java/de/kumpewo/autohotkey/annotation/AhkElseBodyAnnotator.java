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
 * Warning for "else &lt;statement&gt;" on the same line (no "if", no "{"), after if, for, loop, while and try
 * (element ELSE_BRANCH).
 * AHK accepts it, but it is bad style. The parser reports no error for it.
 */
public class AhkElseBodyAnnotator implements Annotator {

    /** Checks every element (see {@link #check}). */
    @Override
    public void annotate(@NotNull PsiElement element, @NotNull AnnotationHolder holder) {
        check(element, holder);
    }

    /** Warning at the body of an else if it is a single statement on the same line. */
    public static void check(@NotNull PsiElement element, @NotNull AnnotationHolder holder) {
        ASTNode body = element.getNode();
        if (body == null || body.getElementType() != AhkTypes.BODY) return;

        // Body of an else: ELSE_BRANCH = KEYWORD_ELSE [CRLF*] BODY
        ASTNode parent = body.getTreeParent();
        if (parent == null || parent.getElementType() != AhkTypes.ELSE_BRANCH) return;

        ASTNode first = body.getFirstChildNode();
        if (first == null) return;
        IElementType firstType = first.getElementType();
        if (firstType == AhkTypes.IF_STMT || firstType == AhkTypes.BLOCK) return;   // "else if" and "else {" are fine

        // Does the body directly follow an "else" (no line break in between)?
        ASTNode prev = body.getTreePrev();
        while (prev != null && prev.getElementType() == TokenType.WHITE_SPACE) prev = prev.getTreePrev();
        if (prev == null || prev.getElementType() != AhkTypes.KEYWORD_ELSE) return;

        holder.newAnnotation(HighlightSeverity.WARNING,
                        AhkBundle.message("annotator.else.body"))
                .range(body)
                .create();
    }
}
