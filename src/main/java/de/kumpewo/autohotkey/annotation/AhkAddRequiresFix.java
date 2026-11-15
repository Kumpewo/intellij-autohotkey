package de.kumpewo.autohotkey.annotation;

import de.kumpewo.autohotkey.AhkBundle;
import com.intellij.codeInsight.intention.IntentionAction;
import com.intellij.codeInsight.intention.PriorityAction;
import com.intellij.openapi.editor.Document;
import com.intellij.openapi.editor.Editor;
import com.intellij.openapi.project.Project;
import com.intellij.psi.PsiDocumentManager;
import com.intellij.psi.PsiFile;
import org.jetbrains.annotations.NotNull;

/**
 * Quick fix for the hint about a missing #Requires (AhkVersionAnnotator): inserts "#Requires AutoHotkey v2.0" or,
 * for code that looks like AutoHotkey v1 ({@link AhkVersionDetector}), "#Requires AutoHotkey v1.1" as the first line
 * of the file.
 */
public class AhkAddRequiresFix implements IntentionAction, PriorityAction {

    static final String LINE = "#Requires AutoHotkey v2.0";
    static final String LINE_V1 = "#Requires AutoHotkey v1.1";

    private final String line;
    private final Priority priority;

    /** Fix for "#Requires AutoHotkey v2.0", at the top of the menu. */
    public AhkAddRequiresFix() {
        this(LINE, Priority.HIGH);
    }

    /** Fix for the given #Requires line at the given position in the menu. */
    AhkAddRequiresFix(@NotNull String line, @NotNull Priority priority) {
        this.line = line;
        this.priority = priority;
    }

    /** Text in the Alt+Enter menu. */
    @Override
    public @NotNull String getText() {
        return AhkBundle.message("intention.add.requires.line", line);
    }

    /** Family name (same as the text). */
    @Override
    public @NotNull String getFamilyName() {
        return AhkBundle.message("intention.add.requires.line", line);
    }

    /** Always available in an AutoHotkey file. */
    @Override
    public boolean isAvailable(@NotNull Project project, Editor editor, PsiFile file) {
        return file != null;   // the platform checks write protection (startInWriteAction)
    }

    /** Inserts the line at the start of the document. */
    @Override
    public void invoke(@NotNull Project project, Editor editor, PsiFile file) {
        Document document = PsiDocumentManager.getInstance(project).getDocument(file);
        if (document == null) return;
        document.insertString(0, line + "\n");
        PsiDocumentManager.getInstance(project).commitDocument(document);
    }

    /** The fix changes the document and therefore runs in a write action. */
    @Override
    public boolean startInWriteAction() {
        return true;
    }

    /** Position in the menu: the fix for the detected version first. */
    @Override
    public @NotNull Priority getPriority() {
        return priority;
    }
}
