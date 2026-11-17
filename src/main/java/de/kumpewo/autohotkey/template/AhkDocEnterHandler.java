package de.kumpewo.autohotkey.template;

import com.intellij.codeInsight.editorActions.enter.EnterHandlerDelegateAdapter;
import com.intellij.openapi.actionSystem.DataContext;
import com.intellij.openapi.editor.Document;
import com.intellij.openapi.editor.Editor;
import com.intellij.openapi.editor.actionSystem.EditorActionHandler;
import com.intellij.openapi.util.Ref;
import com.intellij.psi.PsiDocumentManager;
import com.intellij.psi.PsiElement;
import com.intellij.psi.PsiFile;
import com.intellij.psi.util.PsiTreeUtil;
import de.kumpewo.autohotkey.AhkFile;
import de.kumpewo.autohotkey.documentation.AhkDocComment;
import de.kumpewo.autohotkey.psi.AhkFunc;
import de.kumpewo.autohotkey.psi.AhkTypes;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * Enter in doc comments:
 * <ul>
 *   <li>directly after {@code /**} (nothing else in the line, the comment not yet continued below): closes the
 *       comment; before a function or method with one {@code @param} line per parameter and {@code @returns} if it
 *       returns a value ({@code return x} or {@code =>}); the caret goes to the description line;</li>
 *   <li>inside a doc comment: the new line starts with {@code * } in the same column.</li>
 * </ul>
 */
public class AhkDocEnterHandler extends EnterHandlerDelegateAdapter {

    /** Handles Enter in the two cases above, otherwise leaves it to the platform. */
    @Override
    public Result preprocessEnter(@NotNull PsiFile file, @NotNull Editor editor, @NotNull Ref<Integer> caretOffset,
                                  @NotNull Ref<Integer> caretAdvance, @NotNull DataContext dataContext,
                                  @Nullable EditorActionHandler originalHandler) {
        if (!(file instanceof AhkFile)) return Result.Continue;
        Document document = editor.getDocument();
        int caret = caretOffset.get();
        CharSequence text = document.getCharsSequence();
        int line = document.getLineNumber(caret);
        int lineStart = document.getLineStartOffset(line);
        int lineEnd = document.getLineEndOffset(line);
        String before = text.subSequence(lineStart, caret).toString();
        String after = text.subSequence(caret, lineEnd).toString();
        String indent = before.substring(0, before.length() - before.stripLeading().length());
        if (before.strip().equals("/**") && after.isBlank() && !continuedBelow(document, line)) {
            startDocComment(file, editor, document, caret, lineEnd, indent);
            return Result.Stop;
        }
        if (isInDocComment(file, caret) && before.stripLeading().startsWith("*") && !before.stripLeading().startsWith("*/")) {
            // " * text|" -> new line " * |"
            String prefix = "\n" + indent + "* ";
            document.insertString(caret, prefix);
            editor.getCaretModel().moveToOffset(caret + prefix.length());
            return Result.Stop;
        }
        if (isInDocComment(file, caret) && before.strip().startsWith("/**")) {
            String prefix = "\n" + indent + " * ";
            document.insertString(caret, prefix);
            editor.getCaretModel().moveToOffset(caret + prefix.length());
            return Result.Stop;
        }
        return Result.Continue;
    }

    /** Does the next non-empty line already continue a comment ({@code *} or {@code *}{@code /})? */
    private static boolean continuedBelow(Document document, int line) {
        for (int i = line + 1; i < document.getLineCount(); i++) {
            String next = document.getCharsSequence()
                    .subSequence(document.getLineStartOffset(i), document.getLineEndOffset(i)).toString().strip();
            if (next.isEmpty()) continue;
            return next.startsWith("*");
        }
        return false;
    }

    /** Is the offset inside a doc comment ({@code /**}) that is closed? */
    private static boolean isInDocComment(PsiFile file, int offset) {
        PsiDocumentManager.getInstance(file.getProject()).commitDocument(
                PsiDocumentManager.getInstance(file.getProject()).getDocument(file));
        PsiElement leaf = file.findElementAt(Math.max(0, offset - 1));
        if (leaf == null || leaf.getNode().getElementType() != AhkTypes.BLOCK_COMMENT) return false;
        PsiElement first = leaf;
        while (!first.getText().stripLeading().startsWith("/*")) {
            PsiElement prev = PsiTreeUtil.prevLeaf(first);
            if (prev == null || prev.getNode().getElementType() != AhkTypes.BLOCK_COMMENT) return false;
            first = prev;
        }
        // the lexer splits "/**" into "/*" and "*"
        PsiElement second = PsiTreeUtil.nextLeaf(first);
        boolean doc = first.getText().stripLeading().startsWith("/**") || second != null
                && second.getNode().getElementType() == AhkTypes.BLOCK_COMMENT
                && second.getText().startsWith("*") && !second.getText().startsWith("*/");
        return doc && !leaf.getText().endsWith("*/");
    }

    /**
     * Inserts the closed skeleton after {@code /**}, then (with the committed PSI) the tags for the definition below
     * it, and moves the caret to the description line.
     */
    private static void startDocComment(PsiFile file, Editor editor, Document document, int caret, int lineEnd,
                                        String indent) {
        document.deleteString(caret, lineEnd);
        String descriptionLine = "\n" + indent + " * ";
        document.insertString(caret, descriptionLine + "\n" + indent + " */");
        int descriptionEnd = caret + descriptionLine.length();
        PsiDocumentManager.getInstance(file.getProject()).commitDocument(document);
        PsiElement definition = definitionAfter(file, caret);
        StringBuilder tags = new StringBuilder();
        if (definition instanceof AhkFunc func) {
            for (String name : AhkDocComment.parameterNames(func)) tags.append("\n").append(indent).append(" * @param ").append(name);
            if (returnsValue(func)) tags.append("\n").append(indent).append(" * @returns");
        }
        if (!tags.isEmpty()) document.insertString(descriptionEnd, tags);
        editor.getCaretModel().moveToOffset(descriptionEnd);
    }

    /** The function, method or class whose doc comment ends after {@code offset}. */
    private static @Nullable PsiElement definitionAfter(PsiFile file, int offset) {
        PsiElement leaf = file.findElementAt(offset);
        while (leaf != null && !(leaf.getNode().getElementType() == AhkTypes.BLOCK_COMMENT && leaf.getText().endsWith("*/"))) {
            leaf = PsiTreeUtil.nextLeaf(leaf);
        }
        return leaf == null ? null : AhkDocComment.documentedDefinition(leaf);
    }

    /** Fat-arrow definition or a {@code return} with a value directly in the function (not in nested functions). */
    private static boolean returnsValue(AhkFunc func) {
        if (func.getNode().findChildByType(AhkTypes.FAT_ARROW) != null) return true;
        for (var ret : PsiTreeUtil.findChildrenOfType(func, de.kumpewo.autohotkey.psi.AhkReturnStmt.class)) {
            if (PsiTreeUtil.getParentOfType(ret, AhkFunc.class) == func && ret.getChildren().length > 0) return true;
        }
        return false;
    }
}
