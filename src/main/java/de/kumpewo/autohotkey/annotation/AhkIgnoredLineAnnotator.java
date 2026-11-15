package de.kumpewo.autohotkey.annotation;

import de.kumpewo.autohotkey.AhkBundle;
import com.intellij.lang.annotation.AnnotationHolder;
import com.intellij.lang.annotation.Annotator;
import com.intellij.lang.annotation.HighlightSeverity;
import com.intellij.openapi.editor.HighlighterColors;
import com.intellij.openapi.editor.colors.EditorColorsManager;
import com.intellij.openapi.editor.colors.EditorColorsScheme;
import com.intellij.openapi.editor.markup.TextAttributes;
import com.intellij.openapi.util.TextRange;
import com.intellij.psi.PsiComment;
import com.intellij.psi.PsiElement;
import com.intellij.psi.PsiErrorElement;
import com.intellij.psi.PsiFile;
import com.intellij.psi.PsiWhiteSpace;
import com.intellij.psi.util.PsiTreeUtil;
import de.kumpewo.autohotkey.highlighting.AhkColors;
import de.kumpewo.autohotkey.psi.AhkTypes;
import org.jetbrains.annotations.NotNull;

/**
 * Colors the line after ";@ignore-next-line" uniformly (color AHK_IGNORED_LINE, default: marked background with
 * normal text color), regardless of the other highlighting rules. Annotations are made per leaf element (token)
 * because an annotation should not reach beyond the element currently being processed.
 * AhkAnnotator skips marked lines; the enforced text color overrides the lexer colors.
 */
public class AhkIgnoredLineAnnotator implements Annotator {

    /** Warns at the marker comment and colors the leaves of the marked line. */
    @Override
    public void annotate(@NotNull PsiElement element, @NotNull AnnotationHolder holder) {
        if (element instanceof PsiComment comment && AhkIgnoredLines.isMarker(comment.getText())) {
            warnMarker(comment, holder);
            return;
        }
        if (element.getFirstChild() != null || element instanceof PsiWhiteSpace
                || element.getNode().getElementType() == AhkTypes.CRLF || element.getTextLength() == 0) {
            return;
        }
        PsiFile file = element.getContainingFile();
        if (file == null) return;
        AhkIgnoredLines.Info info = AhkIgnoredLines.info(file);
        if (info.isEmpty()) return;

        TextRange range = element.getTextRange();
        for (TextRange line : info.lines()) {
            if (line.getStartOffset() >= range.getEndOffset()) break;
            TextRange part = line.intersection(range);
            if (part == null || part.isEmpty()) continue;
            holder.newSilentAnnotation(HighlightSeverity.INFORMATION)
                    .range(part)
                    .enforcedTextAttributes(attributes())
                    .create();
        }
    }

    /**
     * Warning at the comment itself: "Parser errors suppressed." or a hint that the marker is unnecessary if the
     * following line has no parser error at all.
     */
    private static void warnMarker(PsiComment comment, AnnotationHolder holder) {
        PsiFile file = comment.getContainingFile();
        if (file == null) return;
        TextRange own = comment.getTextRange();
        TextRange next = null;
        for (TextRange line : AhkIgnoredLines.info(file).lines()) {
            if (line.getStartOffset() > own.getEndOffset()) {
                next = line;
                break;
            }
        }
        boolean suppressed = false;
        if (next != null) {
            for (PsiErrorElement error : PsiTreeUtil.findChildrenOfType(file, PsiErrorElement.class)) {
                int offset = error.getTextRange().getStartOffset();
                if (next.getStartOffset() <= offset && offset <= next.getEndOffset()) {
                    suppressed = true;
                    break;
                }
            }
        }
        // The comment token may contain leading whitespace (" ;@ignore-next-line"): mark from the ";"
        int start = own.getStartOffset() + comment.getText().indexOf(';');
        holder.newAnnotation(HighlightSeverity.WARNING, suppressed
                        ? AhkBundle.message("annotator.ignored.line.used")
                        : AhkBundle.message("annotator.ignored.line.unneeded"))
                .range(new TextRange(start, own.getEndOffset()))
                .create();
    }

    /** Color from the current scheme; without its own text color the normal text color (covers the lexer colors). */
    private static TextAttributes attributes() {
        EditorColorsScheme scheme = EditorColorsManager.getInstance().getGlobalScheme();
        TextAttributes configured = scheme.getAttributes(AhkColors.AHK_IGNORED_LINE);
        TextAttributes result = configured == null ? new TextAttributes() : configured.clone();
        if (result.getForegroundColor() == null) {
            TextAttributes text = scheme.getAttributes(HighlighterColors.TEXT);
            result.setForegroundColor(text != null && text.getForegroundColor() != null
                    ? text.getForegroundColor() : scheme.getDefaultForeground());
        }
        return result;
    }
}
