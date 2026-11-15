package de.kumpewo.autohotkey.annotation;

import com.intellij.openapi.util.TextRange;
import com.intellij.openapi.util.text.StringUtil;
import com.intellij.psi.PsiComment;
import com.intellij.psi.PsiElement;
import com.intellij.psi.PsiFile;
import com.intellij.psi.util.CachedValueProvider;
import com.intellij.psi.util.CachedValuesManager;
import com.intellij.psi.util.PsiTreeUtil;
import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;
import java.util.List;
import java.util.TreeSet;

/**
 * Comment ";@ignore-next-line" (a reason may follow): the next line is parsed normally, but its parser errors are
 * not shown (AhkIgnoredLineErrorFilter) and it is colored uniformly (AhkIgnoredLineAnnotator). Meant for
 * constructs the parser does not know (e.g. "SetTimer () => f()", a call without parentheses).
 * Deliberately applies to exactly ONE line: follow-up errors in later lines stay visible and should rather
 * encourage restructuring the code.
 */
public final class AhkIgnoredLines {

    public static final String MARKER = ";@ignore-next-line";

    /** Text ranges of the marked lines (without line break), sorted ascending. */
    public record Info(List<TextRange> lines) {
        /** No marked lines in the file. */
        public boolean isEmpty() {
            return lines.isEmpty();
        }

        /** Is the offset in a marked line? */
        public boolean contains(int offset) {
            for (TextRange r : lines) {
                if (r.getStartOffset() <= offset && offset <= r.getEndOffset()) return true;
                if (r.getStartOffset() > offset) return false;
            }
            return false;
        }
    }

    private AhkIgnoredLines() {
    }

    /** The marked lines of the file (cached until the file changes). */
    public static @NotNull Info info(@NotNull PsiFile file) {
        return CachedValuesManager.getCachedValue(file, () ->
                CachedValueProvider.Result.create(compute(file), file));
    }

    /** Does the element start in a marked line? */
    public static boolean isIgnored(@NotNull PsiElement element) {
        PsiFile file = element.getContainingFile();
        if (file == null) return false;
        Info info = info(file);
        return !info.isEmpty() && info.contains(element.getTextRange().getStartOffset());
    }

    /** Is the comment text the marker (optionally followed by whitespace and a reason)? */
    static boolean isMarker(@NotNull String commentText) {
        String t = commentText.trim();
        if (!StringUtil.startsWithIgnoreCase(t, MARKER)) return false;
        return t.length() == MARKER.length() || Character.isWhitespace(t.charAt(MARKER.length()));
    }

    /** Finds all marker comments and determines the text range of the line after each. */
    private static Info compute(PsiFile file) {
        CharSequence text = file.getViewProvider().getContents();
        TreeSet<Integer> lineNumbers = new TreeSet<>();
        for (PsiComment comment : PsiTreeUtil.findChildrenOfType(file, PsiComment.class)) {
            if (isMarker(comment.getText())) {
                lineNumbers.add(StringUtil.offsetToLineNumber(text, comment.getTextRange().getEndOffset()) + 1);
            }
        }
        List<TextRange> lines = new ArrayList<>();
        if (lineNumbers.isEmpty()) return new Info(lines);

        // Determine the line starts once
        List<Integer> starts = new ArrayList<>();
        starts.add(0);
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c == '\r' && i + 1 < text.length() && text.charAt(i + 1) == '\n') i++;
            if (c == '\r' || c == '\n') starts.add(i + 1);
        }
        for (int line : lineNumbers) {
            if (line >= starts.size()) continue;
            int start = starts.get(line);
            int end = start;
            while (end < text.length() && text.charAt(end) != '\r' && text.charAt(end) != '\n') end++;
            lines.add(new TextRange(start, end));
        }
        return new Info(lines);
    }
}
