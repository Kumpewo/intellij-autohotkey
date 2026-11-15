package de.kumpewo.autohotkey.annotation;

import de.kumpewo.autohotkey.AhkBundle;
import com.intellij.lang.ASTNode;
import com.intellij.lang.annotation.AnnotationHolder;
import com.intellij.lang.annotation.Annotator;
import com.intellij.lang.annotation.HighlightSeverity;
import com.intellij.psi.PsiElement;
import de.kumpewo.autohotkey.psi.AhkTypes;
import org.jetbrains.annotations.NotNull;

/**
 * Block comment that is not at the start of a line (e.g. after "f() {"). AHK does not recognize it there.
 * The lexer returns BAD_COMMENT for it (up to and including the comment end); the parser skips it like a comment,
 * so there is exactly one error and no follow-up errors.
 */
public class AhkBadCommentAnnotator implements Annotator {

    /** Reports BAD_COMMENT tokens. */
    @Override
    public void annotate(@NotNull PsiElement element, @NotNull AnnotationHolder holder) {
        check(element, holder);
    }

    /** Error at a BAD_COMMENT token (not in files for another AutoHotkey version). */
    public static void check(@NotNull PsiElement element, @NotNull AnnotationHolder holder) {
        ASTNode node = element.getNode();
        if (node == null || node.getElementType() != AhkTypes.BAD_COMMENT) return;
        if (element.getParent() instanceof de.kumpewo.autohotkey.psi.AhkOtherVersionCode) return;   // not a v2 file
        holder.newAnnotation(HighlightSeverity.ERROR, AhkBundle.message("annotator.block.comment.position"))
                .range(element)
                .create();
    }
}
