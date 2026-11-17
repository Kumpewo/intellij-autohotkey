package de.kumpewo.autohotkey.annotation;

import com.intellij.lang.annotation.AnnotationHolder;
import com.intellij.lang.annotation.Annotator;
import com.intellij.lang.annotation.HighlightSeverity;
import com.intellij.openapi.util.TextRange;
import com.intellij.psi.PsiElement;
import de.kumpewo.autohotkey.AhkBundle;
import de.kumpewo.autohotkey.parser.AhkLanguageLevel;
import de.kumpewo.autohotkey.psi.AhkBody;
import de.kumpewo.autohotkey.psi.AhkExprStmt;
import de.kumpewo.autohotkey.psi.AhkFunc;
import de.kumpewo.autohotkey.psi.AhkFuncExpr;
import de.kumpewo.autohotkey.psi.AhkPersistent;
import org.jetbrains.annotations.NotNull;

/**
 * Function definition expressions of AutoHotkey v2.1 ({@code x := f() { ... }}, {@code SetTimer(() { ... }, 100)}):
 * <ul>
 *   <li>a function expression that is a whole statement on its own is a function definition, which AutoHotkey
 *       does not allow there ("Unexpected declaration": {@code (a) { ... }} at the start of a line, {@code f() {}
 *       as the body of if/else/try on the same or the next line); a named function directly after a hotkey or
 *       hotstring ({@code F1::f(hk) { ... }}) is the function of the hotkey and allowed</li>
 *   <li>outside v2.1 files an error "requires AutoHotkey v2.1" on the head (name and parameter list)</li>
 * </ul>
 * The syntax is parsed on every level (Ahk.bnf, func_expr).
 */
public class AhkFuncExprAnnotator implements Annotator {

    /** Checks a function definition expression. */
    @Override
    public void annotate(@NotNull PsiElement element, @NotNull AnnotationHolder holder) {
        if (!(element instanceof AhkFuncExpr expr)) return;
        AhkFunc func = expr.getFunc();
        TextRange head = TextRange.create(expr.getTextRange().getStartOffset(),
                func.getParameterList().getTextRange().getEndOffset());
        String message;
        if (isStatement(expr) && !(func.getName() != null && isHotkeyBody(expr))) {
            message = AhkBundle.message("annotator.funcexpr.statement");
        } else if (AhkLanguageLevel.of(expr) != AhkLanguageLevel.V2_1) {
            message = AhkBundle.message("parser.requires.v21");
        } else {
            return;
        }
        holder.newAnnotation(HighlightSeverity.ERROR, message).range(head).create();
    }

    /** The expression is the whole statement (not part of an assignment, call, list ...). */
    private static boolean isStatement(AhkFuncExpr expr) {
        return expr.getParent() instanceof AhkExprStmt statement && statement.getExprList().size() == 1;
    }

    /** The statement is the body of a hotkey or hotstring on its line ({@code F1::f(hk) { ... }}). */
    private static boolean isHotkeyBody(AhkFuncExpr expr) {
        return expr.getParent().getParent() instanceof AhkBody body && body.getParent() instanceof AhkPersistent;
    }
}
