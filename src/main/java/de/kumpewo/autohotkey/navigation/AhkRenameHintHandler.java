package de.kumpewo.autohotkey.navigation;

import com.intellij.openapi.actionSystem.CommonDataKeys;
import com.intellij.openapi.actionSystem.DataContext;
import com.intellij.openapi.editor.Editor;
import com.intellij.openapi.project.Project;
import com.intellij.psi.PsiElement;
import com.intellij.psi.PsiFile;
import com.intellij.psi.tree.IElementType;
import com.intellij.refactoring.rename.PsiElementRenameHandler;
import com.intellij.refactoring.rename.RenameHandler;
import com.intellij.refactoring.util.CommonRefactoringUtil;
import de.kumpewo.autohotkey.AhkBundle;
import de.kumpewo.autohotkey.AhkFile;
import de.kumpewo.autohotkey.highlighting.AhkNames;
import de.kumpewo.autohotkey.psi.AhkLocals;
import de.kumpewo.autohotkey.psi.AhkMemberExpr;
import de.kumpewo.autohotkey.psi.AhkTypes;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * Rename (Shift+F6) where there is nothing to rename: without it the IDE silently does nothing. Available only in an
 * AutoHotkey editor when the platform finds no element to rename (no target, or a vetoed one such as the help target
 * of a built-in name, AhkRenameVeto); shows a hint at the caret why nothing is renamed:
 * <ul>
 *   <li>a global variable (a free-standing name that is no local variable of a function and no function or class):
 *       not supported, with the tip to use Replace in Files;</li>
 *   <li>a built-in name (variable, function, class, member after the dot, directive);</li>
 *   <li>anything else (keyword, number, string, comment, member without an own definition): what can be renamed.</li>
 * </ul>
 */
public class AhkRenameHintHandler implements RenameHandler {

    /** In an AutoHotkey editor, if the platform has no element to rename. */
    @Override
    public boolean isAvailableOnDataContext(@NotNull DataContext dataContext) {
        Editor editor = CommonDataKeys.EDITOR.getData(dataContext);
        PsiFile file = CommonDataKeys.PSI_FILE.getData(dataContext);
        if (editor == null || !(file instanceof AhkFile)) return false;
        PsiElement element = PsiElementRenameHandler.getElement(dataContext);
        return element == null || PsiElementRenameHandler.isVetoed(element);
    }

    /** Shows the hint at the caret. */
    @Override
    public void invoke(@NotNull Project project, Editor editor, PsiFile file, DataContext dataContext) {
        if (editor == null || file == null) return;
        CommonRefactoringUtil.showErrorHint(project, editor, hint(file, editor.getCaretModel().getOffset()),
                AhkBundle.message("rename.hint.title"), null);
    }

    /** Called without an editor (e.g. from the project view): nothing to show. */
    @Override
    public void invoke(@NotNull Project project, PsiElement @NotNull [] elements, DataContext dataContext) {
    }

    /** The hint for the caret position (see class comment). */
    static @NotNull String hint(@NotNull PsiFile file, int offset) {
        PsiElement leaf = nameAt(file, offset);
        if (leaf != null) {
            IElementType type = leaf.getNode().getElementType();
            String name = leaf.getText();
            if (type == AhkTypes.DIRECTIVE_NAME) {
                return AhkNames.directive(name) != null ? AhkBundle.message("rename.hint.builtin", name)
                        : AhkBundle.message("rename.hint.nothing");
            }
            if (leaf.getParent() instanceof AhkMemberExpr) {
                if (AhkNames.member(name) != null) return AhkBundle.message("rename.hint.builtin", name);
            } else if (AhkNames.nonMember(name) != null) {
                return AhkBundle.message("rename.hint.builtin", name);
            } else if (AhkLocals.isVariablePosition(leaf) && AhkLocals.resolve(leaf) == null) {
                return AhkBundle.message("rename.hint.global", name);
            }
        }
        return AhkBundle.message("rename.hint.nothing");
    }

    /** The identifier or directive name at the offset or directly before it (caret at the end of a name). */
    private static @Nullable PsiElement nameAt(PsiFile file, int offset) {
        for (int at : new int[]{offset, offset - 1}) {
            PsiElement leaf = at < 0 ? null : file.findElementAt(at);
            if (leaf == null) continue;
            IElementType type = leaf.getNode().getElementType();
            if (type == AhkTypes.IDENTIFIER || type == AhkTypes.DIRECTIVE_NAME) return leaf;
        }
        return null;
    }
}
