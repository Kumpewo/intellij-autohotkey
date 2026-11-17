package de.kumpewo.autohotkey.annotation;

import com.intellij.codeInsight.intention.IntentionAction;
import com.intellij.codeInsight.intention.PriorityAction;
import com.intellij.openapi.editor.Document;
import com.intellij.openapi.editor.Editor;
import com.intellij.openapi.project.Project;
import com.intellij.psi.PsiDocumentManager;
import com.intellij.psi.PsiFile;
import de.kumpewo.autohotkey.AhkBundle;
import de.kumpewo.autohotkey.AhkFile;
import de.kumpewo.autohotkey.settings.AhkSettings;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * Intention (Alt+Enter anywhere in a file without {@code #Requires AutoHotkey ...}): inserts the {@code #Requires}
 * line of the file's language level ({@link AhkAddRequiresFix#lineFor}; without {@code #Requires} that is the default
 * level of the settings: {@code #Requires AutoHotkey v2.0}, or for v2.1 the version of the v2.1 interpreter) as the
 * first line, like {@link AhkAddRequiresFix}. The subclass {@link V1}
 * inserts {@code #Requires AutoHotkey v1.1} and is only offered (first) when the syntax detection
 * ({@link AhkVersionDetector}) says the code looks like AutoHotkey v1. Not offered in the first code line of the
 * file, where the hint of {@link AhkVersionAnnotator} already offers the same lines as quick fixes.
 */
public class AhkAddRequiresIntention implements IntentionAction, PriorityAction {

    /** Fixed line, or {@code null} for the line of the default language level. */
    private final @Nullable String fixedLine;

    /** The intention for the line of the default language level. */
    public AhkAddRequiresIntention() {
        this(null);
    }

    /** The intention for the given line ({@code null} = line of the default language level). */
    protected AhkAddRequiresIntention(@Nullable String line) {
        this.fixedLine = line;
    }

    /** The line to insert; a file without {@code #Requires} has the default level of the settings. */
    @NotNull String line() {
        return fixedLine != null ? fixedLine : AhkAddRequiresFix.lineFor(AhkSettings.defaultLevel());
    }

    /** Text in the Alt+Enter menu (the same as the quick fix). */
    @Override
    public @NotNull String getText() {
        return AhkBundle.message("intention.add.requires.line", line());
    }

    /** Family name (settings of the intentions). */
    @Override
    public @NotNull String getFamilyName() {
        return fixedLine != null ? getText() : AhkBundle.message("intention.add.requires.family");
    }

    /** In an AutoHotkey file without {@code #Requires} with a version, outside the range of the hint. */
    @Override
    public boolean isAvailable(@NotNull Project project, Editor editor, PsiFile file) {
        if (editor == null || !(file instanceof AhkFile) || AhkVersionAnnotator.hasVersionRequires(file)) return false;
        return !inHintLine(editor, file);
    }

    /**
     * Is the caret in the line of the hint "#Requires AutoHotkey v2 is missing" (from the first code leaf to the end
     * of its line), where the quick fixes offer the same?
     */
    static boolean inHintLine(@NotNull Editor editor, @NotNull PsiFile file) {
        int first = AhkVersionAnnotator.firstLeafOffset(file);
        if (first < 0) return false;
        Document document = editor.getDocument();
        if (first > document.getTextLength()) return false;
        int caret = editor.getCaretModel().getOffset();
        int lineEnd = document.getLineEndOffset(document.getLineNumber(first));
        return caret >= first && caret <= lineEnd;
    }

    /** Inserts the line at the start of the document. */
    @Override
    public void invoke(@NotNull Project project, Editor editor, PsiFile file) {
        Document document = PsiDocumentManager.getInstance(project).getDocument(file);
        if (document == null) return;
        document.insertString(0, line() + "\n");
        PsiDocumentManager.getInstance(project).commitDocument(document);
    }

    /** Changes the document and therefore runs in a write action. */
    @Override
    public boolean startInWriteAction() {
        return true;
    }

    /** Position in the menu: normal; the v1 variant comes first. */
    @Override
    public @NotNull Priority getPriority() {
        return Priority.NORMAL;
    }

    /** {@code #Requires AutoHotkey v1.1}, only for code that looks like AutoHotkey v1. */
    public static final class V1 extends AhkAddRequiresIntention {

        /** The intention for {@code #Requires AutoHotkey v1.1}. */
        public V1() {
            super(AhkAddRequiresFix.LINE_V1);
        }

        /** Additionally the syntax detection must say v1 (result in the background, until then not offered). */
        @Override
        public boolean isAvailable(@NotNull Project project, Editor editor, PsiFile file) {
            if (!super.isAvailable(project, editor, file)) return false;
            AhkVersionDetector.Result detected =
                    AhkVersionDetector.resultOrRequest(file, file.getViewProvider().getContents());
            return detected != null && detected.major() == 1;
        }

        /** Before the v2.0 variant. */
        @Override
        public @NotNull Priority getPriority() {
            return Priority.HIGH;
        }
    }
}
