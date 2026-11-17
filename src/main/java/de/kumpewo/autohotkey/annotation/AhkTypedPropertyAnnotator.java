package de.kumpewo.autohotkey.annotation;

import com.intellij.lang.ASTNode;
import com.intellij.lang.annotation.AnnotationHolder;
import com.intellij.lang.annotation.Annotator;
import com.intellij.lang.annotation.HighlightSeverity;
import com.intellij.openapi.util.TextRange;
import com.intellij.psi.PsiComment;
import com.intellij.psi.PsiElement;
import com.intellij.psi.PsiErrorElement;
import com.intellij.psi.PsiWhiteSpace;
import com.intellij.psi.tree.IElementType;
import de.kumpewo.autohotkey.AhkBundle;
import de.kumpewo.autohotkey.parser.AhkLanguageLevel;
import de.kumpewo.autohotkey.psi.AhkBlock;
import de.kumpewo.autohotkey.psi.AhkClass;
import de.kumpewo.autohotkey.psi.AhkTypedProperty;
import de.kumpewo.autohotkey.psi.AhkTypes;
import de.kumpewo.autohotkey.psi.AhkUnionBlock;
import org.jetbrains.annotations.NotNull;

/**
 * Typed properties of AutoHotkey v2.1 ({@code x : Int32 := 0}) and union blocks ({@code union { ... }}):
 * <ul>
 *   <li>outside v2.1 files both are an error "requires AutoHotkey v2.1" (the syntax is parsed on every level)</li>
 *   <li>a union block only directly in the body of a class or struct, not nested in another union block</li>
 *   <li>a union block contains only typed properties, each with an optional initializer (AutoHotkey: "Invalid class
 *       variable declaration")</li>
 * </ul>
 * Typed properties exist only in a class body or union block (grammar, class_block). The "{" of a union block must be
 * followed by a line break: AhkBlockBraceAnnotator.
 */
public class AhkTypedPropertyAnnotator implements Annotator {

    /** Checks a typed property, a union block or an item in a union block. */
    @Override
    public void annotate(@NotNull PsiElement element, @NotNull AnnotationHolder holder) {
        if (element instanceof AhkTypedProperty property) {
            checkTypedProperty(property, holder);
        } else if (element instanceof AhkUnionBlock union) {
            checkUnion(union, holder);
        } else if (element.getParent() instanceof AhkBlock block && block.getParent() instanceof AhkUnionBlock
                && AhkLanguageLevel.of(element) == AhkLanguageLevel.V2_1) {
            checkUnionItem(element, holder);
        }
    }

    /** v2.1 only. */
    private static void checkTypedProperty(AhkTypedProperty property, AnnotationHolder holder) {
        if (AhkLanguageLevel.of(property) != AhkLanguageLevel.V2_1) {
            error(holder, declarationRange(property), AhkBundle.message("parser.requires.v21"));
        }
    }

    /** v2.1 only; directly in a class body. */
    private static void checkUnion(AhkUnionBlock union, AnnotationHolder holder) {
        ASTNode keyword = union.getNode().findChildByType(AhkTypes.KEYWORD_UNION);
        if (keyword == null) return;
        if (AhkLanguageLevel.of(union) != AhkLanguageLevel.V2_1) {
            error(holder, keyword.getTextRange(), AhkBundle.message("parser.requires.v21"));
        } else if (!isClassBody(union.getParent())) {
            error(holder, keyword.getTextRange(), AhkBundle.message("annotator.union.misplaced"));
        }
    }

    /**
     * An element directly in the block of a union: only statements of typed properties are allowed; in such a
     * statement each item that is not a typed property is reported (y := 1 in x : Int32, y := 1).
     */
    private static void checkUnionItem(PsiElement element, AnnotationHolder holder) {
        if (element instanceof PsiWhiteSpace || element instanceof PsiComment || element instanceof PsiErrorElement
                || element.getTextLength() == 0) {
            return;
        }
        IElementType type = element.getNode().getElementType();
        if (type == AhkTypes.L_BRACE || type == AhkTypes.R_BRACE || type == AhkTypes.CRLF) return;
        if (type != AhkTypes.TYPED_DECL_STMT) {
            error(holder, element.getTextRange(), AhkBundle.message("annotator.union.content"));
            return;
        }
        for (PsiElement item = element.getFirstChild(); item != null; item = item.getNextSibling()) {
            if (item instanceof AhkTypedProperty || item instanceof PsiWhiteSpace || item instanceof PsiComment
                    || item instanceof PsiErrorElement || item.getTextLength() == 0) {
                continue;
            }
            IElementType t = item.getNode().getElementType();
            if (t == AhkTypes.COMMA || t == AhkTypes.CRLF) continue;
            error(holder, item.getTextRange(), AhkBundle.message("annotator.union.content"));
        }
    }

    /** The block of a class or struct. */
    private static boolean isClassBody(PsiElement element) {
        return element instanceof AhkBlock && element.getParent() instanceof AhkClass;
    }

    /** Name, colon and type of the declaration (without the initializer). */
    private static TextRange declarationRange(AhkTypedProperty property) {
        ASTNode type = property.getNode().findChildByType(AhkTypes.TYPE_SPEC);
        ASTNode last = type != null ? type : property.getNode().findChildByType(AhkTypes.COLON);
        int start = property.getTextRange().getStartOffset();
        return last == null ? property.getTextRange() : new TextRange(start, last.getTextRange().getEndOffset());
    }

    /** Error on a range. */
    private static void error(AnnotationHolder holder, TextRange range, String message) {
        holder.newAnnotation(HighlightSeverity.ERROR, message).range(range).create();
    }
}
