package de.kumpewo.autohotkey.formatter;

import com.intellij.application.options.CodeStyle;
import com.intellij.codeInsight.editorActions.fillParagraph.ParagraphFillHandler;
import com.intellij.openapi.editor.Document;
import com.intellij.openapi.editor.Editor;
import com.intellij.openapi.util.TextRange;
import com.intellij.psi.PsiElement;
import com.intellij.psi.PsiFile;
import com.intellij.psi.codeStyle.CodeStyleSettings;
import com.intellij.psi.tree.IElementType;
import com.intellij.psi.util.PsiTreeUtil;
import de.kumpewo.autohotkey.AhkFileType;
import de.kumpewo.autohotkey.AhkLanguage;
import de.kumpewo.autohotkey.psi.AhkTypes;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;

/**
 * "Edit > Fill Paragraph" for comments: wraps the paragraph at the caret at the right margin of AutoHotkey, see
 * {@link AhkParagraphFiller}. Only comments on lines of their own: line comments ({@code ;}, the neighbouring lines
 * with the same indentation), block comments and doc comments. Code is never changed.
 */
public final class AhkParagraphFillHandler extends ParagraphFillHandler {

    /** Only AutoHotkey files. */
    @Override
    protected boolean isAvailableForFile(@Nullable PsiFile file) {
        return file != null && file.getLanguage() == AhkLanguage.INSTANCE;
    }

    /** Any element: at the end of a line the caret is at the line break, not in the comment. */
    @Override
    protected boolean isAvailableForElement(@Nullable PsiElement element) {
        return element != null;
    }

    /** Fills the paragraph in the line of the caret. */
    @Override
    protected void performOnElement(@NotNull PsiElement element, @NotNull Editor editor) {
        PsiFile file = element.getContainingFile();
        Document document = editor.getDocument();
        int caretLine = document.getLineNumber(editor.getCaretModel().getOffset());
        PsiElement leaf = firstLeaf(file, document, caretLine);
        if (leaf == null) return;

        int first = caretLine;
        int last = caretLine;
        AhkParagraphFiller.Kind kind;
        if (isType(leaf, AhkTypes.LINE_COMMENT)) {
            kind = AhkParagraphFiller.Kind.LINE;
            String indent = indent(document, caretLine);
            while (first > 0 && isLineComment(file, document, first - 1, indent)) first--;
            while (last + 1 < document.getLineCount() && isLineComment(file, document, last + 1, indent)) last++;
        } else if (isType(leaf, AhkTypes.BLOCK_COMMENT)) {
            PsiElement start = leaf;
            PsiElement end = leaf;
            while (!isStart(start) && isType(PsiTreeUtil.prevLeaf(start), AhkTypes.BLOCK_COMMENT)) {
                start = PsiTreeUtil.prevLeaf(start);
            }
            for (PsiElement next = PsiTreeUtil.nextLeaf(end);
                    isType(next, AhkTypes.BLOCK_COMMENT) && !isStart(next); next = PsiTreeUtil.nextLeaf(end)) {
                end = next;
            }
            if (!end.getText().endsWith("*/")) return;   // not closed
            first = document.getLineNumber(start.getTextRange().getStartOffset());
            last = document.getLineNumber(end.getTextRange().getEndOffset());
            // code before or after the comment in its lines
            int column = start.getTextRange().getStartOffset() - document.getLineStartOffset(first);
            if (column != indent(document, first).length()) return;
            TextRange rest = new TextRange(end.getTextRange().getEndOffset(), document.getLineEndOffset(last));
            if (!document.getText(rest).isBlank()) return;
            String text = text(document, first).strip();
            kind = text.startsWith("/**") && !text.startsWith("/**/") ? AhkParagraphFiller.Kind.DOC
                    : AhkParagraphFiller.Kind.BLOCK;
        } else {
            return;
        }

        List<String> lines = new ArrayList<>();
        for (int i = first; i <= last; i++) lines.add(text(document, i));
        CodeStyleSettings settings = CodeStyle.getSettings(file);
        AhkParagraphFiller.Result result = AhkParagraphFiller.fill(lines, caretLine - first, kind,
                settings.getRightMargin(AhkLanguage.INSTANCE), settings.getTabSize(AhkFileType.INSTANCE));
        if (result == null) return;
        int from = document.getLineStartOffset(first + result.first());
        int to = document.getLineEndOffset(first + result.last());
        if (!document.getText(new TextRange(from, to)).equals(result.text())) {
            document.replaceString(from, to, result.text());
        }
    }

    /** The text of a line without its line break. */
    private static String text(Document document, int line) {
        return document.getText(new TextRange(document.getLineStartOffset(line), document.getLineEndOffset(line)));
    }

    /** The leading whitespace of a line. */
    private static String indent(Document document, int line) {
        String text = text(document, line);
        return text.substring(0, text.length() - text.stripLeading().length());
    }

    /** The leaf at the first character of a line that is no whitespace, {@code null} in a blank line. */
    private static @Nullable PsiElement firstLeaf(PsiFile file, Document document, int line) {
        if (text(document, line).isBlank()) return null;
        return file.findElementAt(document.getLineStartOffset(line) + indent(document, line).length());
    }

    /** Is the line a line comment with this indentation? */
    private static boolean isLineComment(PsiFile file, Document document, int line, String indent) {
        return indent(document, line).equals(indent)
                && isType(firstLeaf(file, document, line), AhkTypes.LINE_COMMENT);
    }

    /** Is the leaf the first token of a block comment (not a later one of a comment that is still open)? */
    private static boolean isStart(PsiElement leaf) {
        if (!leaf.getText().startsWith("/*")) return false;
        PsiElement before = PsiTreeUtil.prevLeaf(leaf);
        return !isType(before, AhkTypes.BLOCK_COMMENT) || before.getText().endsWith("*/");
    }

    /** Has the leaf this token type? */
    private static boolean isType(@Nullable PsiElement leaf, IElementType type) {
        return leaf != null && leaf.getNode().getElementType() == type;
    }
}
