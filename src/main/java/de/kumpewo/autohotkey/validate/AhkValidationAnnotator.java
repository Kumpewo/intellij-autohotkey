package de.kumpewo.autohotkey.validate;

import com.intellij.lang.annotation.AnnotationHolder;
import com.intellij.lang.annotation.Annotator;
import com.intellij.lang.annotation.HighlightSeverity;
import com.intellij.openapi.editor.Document;
import com.intellij.openapi.util.TextRange;
import com.intellij.psi.PsiDocumentManager;
import com.intellij.psi.PsiElement;
import com.intellij.psi.PsiFile;
import de.kumpewo.autohotkey.AhkBundle;
import de.kumpewo.autohotkey.AhkLanguage;
import org.jetbrains.annotations.NotNull;

/**
 * Marks the line of the error that AutoHotkey reported in its last validation (AhkValidator), as long as the file
 * has not changed since. The whole line without leading and trailing blanks is marked, because AutoHotkey reports
 * only the line.
 */
public class AhkValidationAnnotator implements Annotator {

    /** Annotates the error line of the file (works on the file as a whole). */
    @Override
    public void annotate(@NotNull PsiElement element, @NotNull AnnotationHolder holder) {
        if (!(element instanceof PsiFile file) || file.getLanguage() != AhkLanguage.INSTANCE) return;
        AhkValidation.Result result = AhkValidation.getInstance(file.getProject()).current(file);
        if (result == null || result.line() <= 0) return;
        Document document = PsiDocumentManager.getInstance(file.getProject()).getDocument(file);
        if (document == null || result.line() > document.getLineCount()) return;
        int start = document.getLineStartOffset(result.line() - 1);
        int end = document.getLineEndOffset(result.line() - 1);
        CharSequence text = document.getCharsSequence();
        while (start < end && Character.isWhitespace(text.charAt(start))) start++;
        while (end > start && Character.isWhitespace(text.charAt(end - 1))) end--;
        var builder = holder.newAnnotation(HighlightSeverity.ERROR,
                AhkBundle.message("validate.annotation", result.message()));
        if (start < end) {
            builder.range(new TextRange(start, end)).create();
        } else {
            builder.range(new TextRange(start, start)).afterEndOfLine().create();
        }
    }
}
