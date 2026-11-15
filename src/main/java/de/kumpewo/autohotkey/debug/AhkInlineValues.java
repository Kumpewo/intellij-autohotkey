package de.kumpewo.autohotkey.debug;

import com.intellij.openapi.editor.Document;
import com.intellij.psi.PsiDocumentManager;
import com.intellij.psi.PsiElement;
import com.intellij.psi.PsiFile;
import com.intellij.psi.tree.IElementType;
import com.intellij.psi.util.PsiTreeUtil;
import de.kumpewo.autohotkey.psi.AhkTypes;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.TreeSet;

/**
 * Lines where the debugger shows a variable's value inline ({@code name = value} at the end of the line): the
 * uses of the name (variables and parameters, case-insensitive) in the scope of the current line, up to that line.
 * The scope is the innermost function (also a method, accessor or arrow function) containing the line, or the code
 * outside all functions and classes; nested functions are other scopes.
 */
public final class AhkInlineValues {

    private AhkInlineValues() {
    }

    /** 0-based lines of the uses of {@code name} in the scope of {@code line} (0-based), up to that line. */
    public static @NotNull List<Integer> lines(@NotNull PsiFile file, @NotNull String name, int line) {
        Document document = PsiDocumentManager.getInstance(file.getProject()).getDocument(file);
        if (document == null || line < 0 || line >= document.getLineCount()) return List.of();
        PsiElement scope = scopeAt(file, document, line);
        TreeSet<Integer> lines = new TreeSet<>();
        PsiElement root = scope == null ? file : scope;
        for (PsiElement leaf : PsiTreeUtil.collectElements(root, e -> isUse(e, name))) {
            if (scopeOf(leaf) != scope) continue;
            int useLine = document.getLineNumber(leaf.getTextOffset());
            if (useLine <= line) lines.add(useLine);
        }
        return new ArrayList<>(lines);
    }

    /** True for an identifier with the name used as a variable or declared as a parameter. */
    private static boolean isUse(@NotNull PsiElement element, @NotNull String name) {
        if (element.getFirstChild() != null || element.getNode() == null) return false;
        if (element.getNode().getElementType() != AhkTypes.IDENTIFIER || !element.getText().equalsIgnoreCase(name)) {
            return false;
        }
        PsiElement parent = element.getParent();
        if (parent == null) return false;
        IElementType type = parent.getNode().getElementType();
        return type == AhkTypes.REF_EXPR || type == AhkTypes.PARAMETER;
    }

    /** The scope of the line: function containing its first code, or {@code null} for the top level. */
    private static @Nullable PsiElement scopeAt(@NotNull PsiFile file, @NotNull Document document, int line) {
        int start = document.getLineStartOffset(line);
        int end = document.getLineEndOffset(line);
        String text = document.getCharsSequence().subSequence(start, end).toString();
        int indent = 0;
        while (indent < text.length() && Character.isWhitespace(text.charAt(indent))) indent++;
        PsiElement leaf = file.findElementAt(start + Math.min(indent, Math.max(0, text.length() - 1)));
        return leaf == null ? null : scopeOf(leaf);
    }

    /** Innermost function around the element, {@code null} at the top level, the class for code in a class. */
    private static @Nullable PsiElement scopeOf(@NotNull PsiElement element) {
        PsiElement scope = PsiTreeUtil.findFirstParent(element, true, e -> e.getNode() != null && isScope(e.getNode().getElementType()));
        return scope;
    }

    /** Functions, accessors, arrow functions and classes are scopes. */
    private static boolean isScope(@NotNull IElementType type) {
        return type == AhkTypes.FUNC || type == AhkTypes.ACCESSOR || type == AhkTypes.LAMBDA_EXPR
                || type == AhkTypes.CLASS_PROPERTY || type == AhkTypes.CLASS;
    }
}
