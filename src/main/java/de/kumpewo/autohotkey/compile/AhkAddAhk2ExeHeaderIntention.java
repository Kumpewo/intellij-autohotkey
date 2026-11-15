package de.kumpewo.autohotkey.compile;

import com.intellij.codeInsight.intention.IntentionAction;
import com.intellij.codeInsight.intention.preview.IntentionPreviewInfo;
import com.intellij.openapi.editor.Editor;
import com.intellij.openapi.project.Project;
import com.intellij.psi.PsiFile;
import de.kumpewo.autohotkey.AhkBundle;
import org.jetbrains.annotations.NotNull;

/**
 * Intention (Alt+Enter, no warning) "Add Ahk2Exe compiler directives": inserts the head of {@link AhkAhk2ExeHeader}
 * into a script without {@code ;@Ahk2Exe-Base}, anywhere in the file.
 */
public final class AhkAddAhk2ExeHeaderIntention implements IntentionAction {

    /** Text in the Alt+Enter list. */
    @Override
    public @NotNull String getText() {
        return AhkBundle.message("intention.add.ahk2exe");
    }

    /** Family name (settings of the intentions). */
    @Override
    public @NotNull String getFamilyName() {
        return AhkBundle.message("intention.add.ahk2exe");
    }

    /** Available in AutoHotkey files without base directive. */
    @Override
    public boolean isAvailable(@NotNull Project project, Editor editor, PsiFile file) {
        return editor != null && AhkAhk2ExeHeader.isApplicable(file);
    }

    /** Inserts the head as live template. */
    @Override
    public void invoke(@NotNull Project project, Editor editor, PsiFile file) {
        if (editor != null && file != null) AhkAhk2ExeHeader.insert(project, editor, file);
    }

    /**
     * In a write action: without one the platform keeps the document read-only while the intention runs, and the
     * template could not insert its text.
     */
    @Override
    public boolean startInWriteAction() {
        return true;
    }

    /** No preview: the head is a live template with placeholders. */
    @Override
    public @NotNull IntentionPreviewInfo generatePreview(@NotNull Project project, @NotNull Editor editor,
                                                         @NotNull PsiFile file) {
        return IntentionPreviewInfo.EMPTY;
    }
}
