package de.kumpewo.autohotkey.annotation;

import de.kumpewo.autohotkey.AhkBundle;
import com.intellij.lang.ASTNode;
import com.intellij.lang.annotation.AnnotationHolder;
import com.intellij.lang.annotation.Annotator;
import com.intellij.lang.annotation.HighlightSeverity;
import com.intellij.psi.PsiElement;
import com.intellij.psi.TokenType;
import com.intellij.psi.tree.IElementType;
import de.kumpewo.autohotkey.psi.AhkTokenSets;
import de.kumpewo.autohotkey.psi.AhkTypes;
import org.jetbrains.annotations.NotNull;

/**
 * Declaration without a variable name (DECL_STMT without DECL_ITEM).
 * Only "global" or "static" alone as the first statement in the body of a function, method, hotkey or hotstring is
 * allowed (assume-global/assume-static: all variables of the function are global/static then). "local" always needs a
 * name. Otherwise an error at the keyword.
 * The parser accepts the keyword alone everywhere so that it can be reported here specifically.
 */
public class AhkDeclAnnotator implements Annotator {

    /** Reports {@code local} without a name and a misplaced {@code global}/{@code static}. */
    @Override
    public void annotate(@NotNull PsiElement element, @NotNull AnnotationHolder holder) {
        ASTNode decl = element.getNode();
        if (decl == null || decl.getElementType() != AhkTypes.DECL_STMT) return;
        if (decl.findChildByType(AhkTypes.DECL_ITEM) != null) return;

        ASTNode keyword = decl.findChildByType(AhkTypes.KEYWORD_DECL);
        if (keyword == null) return;
        String word = keyword.getText();

        if ("local".equalsIgnoreCase(word)) {
            holder.newAnnotation(HighlightSeverity.ERROR, AhkBundle.message("annotator.decl.needs.name", word))
                    .range(keyword)
                    .create();
        } else if (!isFirstStatementOfFunction(decl)) {
            holder.newAnnotation(HighlightSeverity.ERROR,
                            AhkBundle.message("annotator.global.first.statement", word))
                    .range(keyword)
                    .create();
        }
    }

    /**
     * Is the statement directly in the block of a function, method or hotkey (in AHK v2 also a function), with only
     * "{", line breaks and comments before it?
     */
    private static boolean isFirstStatementOfFunction(@NotNull ASTNode stmt) {
        ASTNode block = stmt.getTreeParent();
        if (block == null || block.getElementType() != AhkTypes.BLOCK) return false;
        if (!isFunctionBlock(block)) return false;

        for (ASTNode prev = stmt.getTreePrev(); prev != null; prev = prev.getTreePrev()) {
            IElementType type = prev.getElementType();
            if (type == AhkTypes.L_BRACE) return true;
            if (type != AhkTypes.CRLF && type != TokenType.WHITE_SPACE && !AhkTokenSets.COMMENTS.contains(type)) {
                return false;
            }
        }
        return false;
    }

    /**
     * Block of a function/method (FUNC > BLOCK), a hotkey or a hotstring (PERSISTENT):
     * "F1::⏎{" and "::btw::⏎{" are PERSISTENT > BLOCK, "F1:: {" and ":X:btw:: {" PERSISTENT > BODY > BLOCK.
     */
    private static boolean isFunctionBlock(@NotNull ASTNode block) {
        ASTNode owner = block.getTreeParent();
        if (owner == null) return false;
        if (owner.getElementType() == AhkTypes.FUNC) return true;
        if (owner.getElementType() == AhkTypes.BODY) owner = owner.getTreeParent();
        return owner != null && owner.getElementType() == AhkTypes.PERSISTENT;
    }
}
