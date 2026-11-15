package de.kumpewo.autohotkey.completion;

import com.intellij.lang.ASTNode;
import com.intellij.psi.PsiElement;
import com.intellij.psi.PsiFile;
import com.intellij.psi.PsiWhiteSpace;
import com.intellij.psi.util.PsiTreeUtil;
import de.kumpewo.autohotkey.psi.*;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * Arguments of a command at a text position, for parameter info and value suggestions:
 * <ul>
 *   <li>Call {@code CoordMode("Mouse", |)}: owner is the ARG_LIST, name the called simple name</li>
 *   <li>Command {@code CoordMode "Mouse", |}: owner is the COMMAND_STMT, name the first word</li>
 *   <li>Command without arguments {@code MsgBox |}: owner is the EXPR_STMT with the single name</li>
 *   <li>Directive {@code #Warn All, |}: owner is the DIRECTIVE, arguments in the token DIRECTIVE_ARGS</li>
 *   <li>Method {@code obj.Add(|}: like a call, name = method name, plus the expression left of the dot
 *       ({@link Owner#qualifier}). The command form {@code obj.Add "x", |} does not exist: the grammar reads it
 *       as a concatenation (command_stmt only starts with a simple name).</li>
 * </ul>
 * The current argument (from 0) is the number of commas of the owner before the position (omitted arguments
 * {@code f(a,,b)} count, commas in nested calls don't).
 */
public final class AhkArguments {

    /**
     * Owner of the arguments and name of the command. For a method ({@code method}) {@code name} is the method
     * name and {@code qualifier} the expression left of the dot.
     */
    public record Owner(@NotNull PsiElement element, @NotNull String name, @Nullable PsiElement qualifier,
                        boolean method) {
        /** Owner of a function, command or directive (not a method). */
        public Owner(@NotNull PsiElement element, @NotNull String name) {
            this(element, name, null, false);
        }
    }

    private AhkArguments() {
    }

    /** Innermost command whose arguments contain {@code offset}, otherwise null. */
    public static @Nullable Owner find(@NotNull PsiFile file, int offset) {
        PsiElement leaf = file.findElementAt(offset);
        Owner owner = walkUp(leaf, offset);
        if (owner != null) return owner;
        // Caret after the end of the command on the same line ("CoordMode "a", |", "MsgBox |"): whitespace at the
        // end of the line no longer belongs to the command, so search from the last code leaf before (not across CRLF)
        PsiElement prev = prevCodeLeafOnLine(file, offset);
        if (prev == null) return null;
        owner = walkUp(prev, offset);
        if (owner != null) return owner;
        // "MsgBox |": without arguments it is not a command yet, but a single name
        if (prev.getNode().getElementType() == AhkTypes.IDENTIFIER && prev.getParent() instanceof AhkRefExpr ref
                && ref.getParent() instanceof AhkExprStmt stmt && stmt.getFirstChild() == ref
                && stmt.getLastChild() == ref && ref.getTextRange().getEndOffset() < offset) {
            return new Owner(stmt, prev.getText());
        }
        return null;
    }

    /** First owner on the way from the leaf up to the file. */
    private static @Nullable Owner walkUp(@Nullable PsiElement leaf, int offset) {
        for (PsiElement e = leaf; e != null && !(e instanceof PsiFile); e = e.getParent()) {
            Owner owner = ownerOf(e, offset);
            if (owner != null) return owner;
        }
        return null;
    }

    /** Last leaf before {@code offset} that is not whitespace, provided there is no line end in between. */
    private static @Nullable PsiElement prevCodeLeafOnLine(PsiFile file, int offset) {
        if (offset <= 0) return null;
        PsiElement e = file.findElementAt(offset - 1);
        while (e instanceof PsiWhiteSpace) {
            if (e.getText().indexOf('\n') >= 0 || e.getText().indexOf('\r') >= 0) return null;
            e = PsiTreeUtil.prevLeaf(e);
        }
        if (e == null || e.getNode().getElementType() == AhkTypes.CRLF) return null;
        return e;
    }

    /** Owner if {@code e} is an argument list, command or directive whose arguments contain {@code offset}. */
    private static @Nullable Owner ownerOf(PsiElement e, int offset) {
        if (e instanceof AhkArgList && e.getParent() instanceof AhkCallExpr call) {
            PsiElement callee = call.getFirstChild();
            boolean afterParen = offset > e.getTextRange().getStartOffset();
            boolean beforeEnd = offset < e.getTextRange().getEndOffset()
                    || e.getLastChild().getNode().getElementType() != AhkTypes.R_PAREN;
            if (!afterParen || !beforeEnd) return null;
            if (callee instanceof AhkRefExpr) return new Owner(e, callee.getText());
            if (callee instanceof AhkMemberExpr member) return methodOwner(e, member);
            return null;
        }
        if (e instanceof AhkCommandStmt) {
            PsiElement name = e.getFirstChild();
            if (name == null || offset <= name.getTextRange().getEndOffset()) return null;
            return new Owner(e, name.getText());
        }
        if (e instanceof AhkDirective) {
            ASTNode name = e.getNode().findChildByType(AhkTypes.DIRECTIVE_NAME);
            if (name != null && offset > name.getTextRange().getEndOffset()) return new Owner(e, name.getText());
            return null;
        }
        return null;
    }

    /**
     * Method as owner: only a whole name directly after the dot ({@code obj.Add}, not {@code obj.%x%} or
     * {@code obj.a%b%}), otherwise null.
     */
    private static @Nullable Owner methodOwner(PsiElement element, AhkMemberExpr member) {
        PsiElement name = member.getLastChild();
        if (name == null || name.getNode().getElementType() != AhkTypes.IDENTIFIER) return null;
        PsiElement dot = name.getPrevSibling();
        if (dot == null || dot.getNode().getElementType() != AhkTypes.DOT) return null;
        return new Owner(element, name.getText(), member.getFirstChild(), true);
    }

    /** Number (from 0) of the argument at {@code offset}. */
    public static int index(@NotNull PsiElement owner, int offset) {
        if (owner instanceof AhkDirective) {
            ASTNode args = owner.getNode().findChildByType(AhkTypes.DIRECTIVE_ARGS);
            if (args == null) return 0;
            int end = Math.min(args.getTextLength(), Math.max(0, offset - args.getStartOffset()));
            return (int) args.getText().substring(0, end).chars().filter(c -> c == ',').count();
        }
        int index = 0;
        for (PsiElement child = owner.getFirstChild(); child != null; child = child.getNextSibling()) {
            if (child.getTextRange().getStartOffset() >= offset) break;
            if (child.getNode().getElementType() == AhkTypes.COMMA) index++;
        }
        return index;
    }

    /**
     * Is {@code argument} (an expression) a whole argument of a command? Then its owner, otherwise null.
     * For value suggestions: only {@code CoordMode "Mo|"}, not {@code CoordMode "a" . "Mo|"}.
     */
    public static @Nullable Owner ownerOfArgument(@NotNull PsiElement argument) {
        PsiElement parent = argument.getParent();
        if (parent instanceof AhkArgList || parent instanceof AhkCommandStmt) {
            return ownerOf(parent, argument.getTextRange().getStartOffset() + 1);
        }
        return null;
    }
}
