package de.kumpewo.autohotkey.completion;

import com.intellij.codeInsight.AutoPopupController;
import com.intellij.codeInsight.editorActions.TypedHandlerDelegate;
import com.intellij.openapi.editor.Document;
import com.intellij.openapi.editor.Editor;
import com.intellij.openapi.editor.ex.EditorEx;
import com.intellij.openapi.editor.highlighter.HighlighterIterator;
import com.intellij.openapi.fileTypes.FileType;
import com.intellij.openapi.project.Project;
import com.intellij.psi.PsiFile;
import de.kumpewo.autohotkey.AhkFile;
import de.kumpewo.autohotkey.highlighting.AhkBraceMatcher;
import de.kumpewo.autohotkey.psi.AhkTypes;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * Typing in the AHK editor:
 * <ul>
 *   <li>open the completion popup automatically after "." (member) and "#" (directive) as well; for letters the
 *       IDE does it by itself, also in strings (key names after "{" in a Send string, from the first letter).
 *       Deliberately not after "{": otherwise the popup would be open after "F1:: {" and Enter would insert a
 *       suggestion instead of breaking the line.</li>
 *   <li>in the argument of #Include/#IncludeAgain also after "&lt;", quotes, "\" and "/" (file paths,
 *       AhkIncludeCompletion)</li>
 *   <li>show the parameter info after "(" (AhkParameterInfoHandler)</li>
 *   <li>"%" in pairs like quotes (AhkQuoteHandler): an opening "%" brings the closing one (caret in between), a
 *       closing one is not doubled, and an existing one is skipped. Opening/closing as for the brace pair
 *       (AhkBraceMatcher.percentsBefore). Only where the lexer sees a PERCENT token, so not in strings, comments and
 *       directive arguments.</li>
 * </ul>
 */
public class AhkTypedHandler extends TypedHandlerDelegate {

    /** Completion popup after "." and "#" and in #Include paths, parameter info after "(". */
    @Override
    public @NotNull Result checkAutoPopup(char c, @NotNull Project project, @NotNull Editor editor,
                                          @NotNull PsiFile file) {
        if (file instanceof AhkFile && (c == '.' || c == '#')) {
            AutoPopupController.getInstance(project).scheduleAutoPopup(editor);
            return Result.STOP;
        }
        if (file instanceof AhkFile && "<\"'\\/".indexOf(c) >= 0) {
            // path in an #Include argument (AhkIncludeCompletion)
            int caret = editor.getCaretModel().getOffset();
            com.intellij.openapi.editor.Document doc = editor.getDocument();
            int lineStart = doc.getLineStartOffset(doc.getLineNumber(caret));
            if (AhkIncludeCompletion.isIncludeArgument(doc.getCharsSequence().subSequence(lineStart, caret) + String.valueOf(c))) {
                AutoPopupController.getInstance(project).scheduleAutoPopup(editor);
                return Result.STOP;
            }
        }
        if (file instanceof AhkFile && c == '(') {
            // show the parameter info right with the bracket, as in Java (IDE setting "Parameter Info")
            AutoPopupController.getInstance(project).autoPopupParameterInfo(editor, null);
        }
        return Result.CONTINUE;
    }

    /** Typing a closing "%" over an existing one: only move the caret. */
    @Override
    public @NotNull Result beforeCharTyped(char c, @NotNull Project project, @NotNull Editor editor,
                                           @NotNull PsiFile file, @NotNull FileType fileType) {
        if (c != '%' || !(file instanceof AhkFile)) return Result.CONTINUE;
        int offset = editor.getCaretModel().getOffset();
        Document doc = editor.getDocument();
        if (offset >= doc.getTextLength() || doc.getCharsSequence().charAt(offset) != '%') return Result.CONTINUE;
        HighlighterIterator it = percentAt(editor, offset);
        if (it == null || AhkBraceMatcher.percentsBefore(it) % 2 == 0) return Result.CONTINUE;   // opens
        editor.getCaretModel().moveToOffset(offset + 1);
        return Result.STOP;
    }

    /** After an opening "%" insert the closing one; the caret stays in between. */
    @Override
    public @NotNull Result charTyped(char c, @NotNull Project project, @NotNull Editor editor,
                                     @NotNull PsiFile file) {
        if (c != '%' || !(file instanceof AhkFile)) return Result.CONTINUE;
        int offset = editor.getCaretModel().getOffset();   // after the typed "%"
        HighlighterIterator it = percentAt(editor, offset - 1);
        if (it == null || AhkBraceMatcher.percentsBefore(it) % 2 != 0) return Result.CONTINUE;   // closes
        editor.getDocument().insertString(offset, "%");
        return Result.STOP;
    }

    /** Iterator on the PERCENT token at {@code offset}, otherwise null (string, comment, directive ...). */
    private static @Nullable HighlighterIterator percentAt(Editor editor, int offset) {
        if (offset < 0 || !(editor instanceof EditorEx ex)) return null;
        HighlighterIterator it = ex.getHighlighter().createIterator(offset);
        if (it.atEnd() || it.getTokenType() != AhkTypes.PERCENT || it.getStart() != offset) return null;
        return it;
    }
}
